package com.retropro.feature.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * SenseVoice-Small 本地语音识别（sherpa-onnx int8 量化，离线，不联网）。
 *
 * ## 模型来源（魔搭社区镜像）
 * `gomodels/sherpa` 仓库下的 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`
 * （原 sherpa-onnx 官方 release 的国内镜像），打包进 assets：
 *  - `asr/model.int8.onnx`（228MB，int8 量化，中/英/日/韩/粤语）
 *  - `asr/tokens.txt`
 *
 * ## 生命周期
 * 模型常驻内存约 300MB，进程内只建一次 [OfflineRecognizer]；
 * 首次调用 [ensureReady] 会把 assets 拷到 filesDir（sherpa-onnx 必须从文件路径加载）。
 *
 * ## 录音
 * SenseVoice 是**离线（非流式）**模型：整段录完再解码，没有中间结果。
 * [startRecording] 用 AudioRecord 16k/mono/PCM16 攒样本，[stopAndDecode] 停止并解码。
 */
object LocalAsr {

    private const val ASR_DIR = "asr"
    private const val SAMPLE_RATE = 16000
    private const val TAG_REGEX = "<\\|[^>]*\\|>"  // SenseVoice 输出带 <|zh|><|NEUTRAL|> 等标记

    private val lock = Any()
    private var chunks = ArrayList<FloatArray>()
    private var sampleCount = 0

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recordingStart = 0L

    /** 初始化失败的原因，展示给用户（M0 调试卡） */
    @Volatile var initError: String? = null
        private set

    val isReady: Boolean get() = recognizer != null
    val isRecording: Boolean get() = recorder != null

    fun elapsedSeconds(): Int =
        if (recorder == null) 0 else ((System.currentTimeMillis() - recordingStart) / 1000).toInt()

    // ------------------------------------------------------------ 初始化

    /** 拷 assets → filesDir → 建 OfflineRecognizer。幂等，失败返回 false 并记 [initError] */
    fun ensureReady(context: Context): Boolean {
        if (recognizer != null) return true
        return synchronized(this) {
            if (recognizer != null) return true
            try {
                val dir = File(context.filesDir, ASR_DIR)
                if (!dir.exists()) dir.mkdirs()
                copyAssetIfNeeded(context, "$ASR_DIR/model.int8.onnx", File(dir, "model.int8.onnx"))
                copyAssetIfNeeded(context, "$ASR_DIR/tokens.txt", File(dir, "tokens.txt"))

                // API 已按项目规矩用 javap 对 app/libs/sherpa-onnx-1.13.8.aar 核实：
                // OfflineRecognizerConfig(featConfig, modelConfig)；
                // OfflineModelConfig(senseVoice=, tokens=, numThreads=, provider=)；
                // OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization)
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = File(dir, "model.int8.onnx").absolutePath,
                                language = "zh",
                                useInverseTextNormalization = true,
                            ),
                            tokens = File(dir, "tokens.txt").absolutePath,
                            numThreads = 2,
                            debug = false,
                            provider = "cpu",
                        ),
                    ),
                )
                true
            } catch (t: Throwable) {
                initError = t.message ?: t.javaClass.simpleName
                false
            }
        }
    }

    private fun copyAssetIfNeeded(context: Context, assetPath: String, dst: File) {
        if (dst.exists() && dst.length() > 0) return
        context.assets.open(assetPath).use { input ->
            dst.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        }
    }

    // ------------------------------------------------------------ 录音

    /** 开始录音。返回 false = 麦克风不可用（权限/被占用） */
    fun startRecording(): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(lock) {
            chunks = ArrayList()
            sampleCount = 0
        }
        recordingStart = System.currentTimeMillis()
        rec.startRecording()
        recorder = rec
        thread(name = "local-asr-record") {
            val buf = ShortArray(SAMPLE_RATE / 10)  // 100ms 一批
            while (recorder === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val f = FloatArray(n) { buf[it] / 32768f }
                    synchronized(lock) {
                        chunks.add(f)
                        sampleCount += n
                    }
                } else if (n < 0) break
            }
        }
        return true
    }

    /** 停止录音并解码（阻塞，UI 层放到后台线程调）。返回去掉 <|...|> 标记后的文本 */
    fun stopAndDecode(): String {
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        rec?.release()

        val all = synchronized(lock) {
            FloatArray(sampleCount).also { arr ->
                var offset = 0
                chunks.forEach { c ->
                    c.copyInto(arr, offset)
                    offset += c.size
                }
                chunks = ArrayList()
                sampleCount = 0
            }
        }
        if (all.size < SAMPLE_RATE / 2) return ""  // 短于 0.5s 视为没说话

        val engine = recognizer ?: return ""
        val stream = engine.createStream()
        stream.acceptWaveform(all, SAMPLE_RATE)
        engine.decode(stream)
        val text = engine.getResult(stream).text
        return text.replace(Regex(TAG_REGEX), "").trim()
    }

    /** token 级解码结果：保留事件标签（<|Breath|> 等）与每个 token 的时间戳 */
    data class TokenResult(
        val tokens: List<String>,
        val timestamps: List<Float>,  // 秒，与 tokens 一一对应
        val raw: String,
    )

    /**
     * 解码一段已有音频样本（GlobalAnalyzer 的 VAD 分段逐段调用）。
     * 返回 null = 引擎未初始化。
     */
    internal fun decodeTokens(samples: FloatArray): TokenResult? {
        val engine = recognizer ?: return null
        if (samples.isEmpty()) return null
        val stream = engine.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        engine.decode(stream)
        val r = engine.getResult(stream)
        return TokenResult(
            tokens = r.tokens.toList(),
            timestamps = r.timestamps.toList(),
            raw = r.text,
        )
    }

    /** 释放引擎（换模型/省内存时用；一般进程级单例不释放） */
    fun release() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }

    // ------------------------------------------------------------ 文件解码（M0 调试用）

    /**
     * 解码一个 wav 文件（16-bit PCM，16k 单声道；22k 也能解，会走内部重采样）。
     * 返回 null = 引擎未初始化；空串 = 没识别出内容。
     */
    fun decodeWavFile(file: File): String? {
        val engine = recognizer ?: return null
        android.util.Log.i("LocalAsr", "decode file=${file.absolutePath} exists=${file.exists()} size=${file.length()}")
        val samples = readWavPcm16(file)
        android.util.Log.i("LocalAsr", "parsed samples=${samples?.size}")
        if (samples == null) return ""
        if (samples.isEmpty()) return ""
        val stream = engine.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        engine.decode(stream)
        val result = engine.getResult(stream)
        val raw = result.text
        val stripped = raw.replace(Regex(TAG_REGEX), "").trim()
        android.util.Log.i("LocalAsr", "lang=${result.lang} raw='$raw' tokens=${result.tokens.size}")
        // 调试场景：剥完标记为空但原始输出非空时，把原始输出亮出来方便定位
        return stripped.ifBlank { "原始输出: [$raw] lang=${result.lang} tokens=${result.tokens.size}" }
    }

    /** 最小 wav 解析：找 data 块，读 16-bit PCM，立体声取平均 */
    internal fun readWavPcm16(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        var pos = 12  // 跳过 RIFF/WAVE/fmt 头部起点，逐块找 data
        var dataStart = -1
        var dataLen = 0
        var channels = 1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = java.nio.ByteBuffer.wrap(bytes, pos + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (id == "fmt ") {
                channels = java.nio.ByteBuffer.wrap(bytes, pos + 10, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            }
            if (id == "data") {
                dataStart = pos + 8
                dataLen = size
                break
            }
            pos += 8 + size + (size and 1)
        }
        if (dataStart < 0) return null
        val frameCount = dataLen / 2 / channels
        val buf = java.nio.ByteBuffer.wrap(bytes, dataStart, frameCount * 2 * channels)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        FloatArray(frameCount) {
            var v = 0
            repeat(channels) { v += buf.short.toInt() }
            v / channels / 32768f
        }
    }.getOrNull()
}
