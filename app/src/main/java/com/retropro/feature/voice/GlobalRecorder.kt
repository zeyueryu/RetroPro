package com.retropro.feature.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * 整场录音（长音频）—— 边录边写 WAV 文件，供 [GlobalAnalyzer] 全局分析。
 *
 * 与快速录入（内存攒样本，几十秒量级）不同：整场录音可能一两个小时，
 * 必须流式落盘。WAV 头先写占位，[stop] 时回填真实长度。
 */
class GlobalRecorder(context: Context) {

    private val file = File(context.filesDir, "global_rec.wav")
    private var recorder: AudioRecord? = null
    private var startedAt = 0L
    private var totalSamples = 0

    val outputFile: File get() = file
    val isRecording: Boolean get() = recorder != null
    fun elapsedSeconds(): Int = if (recorder == null) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    fun start(): Boolean {
        if (isRecording) return true
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sr * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        // 占位头 + 截断旧文件
        RandomAccessFile(file, "rw").use { it.setLength(0); writeWavHeader(it, 0, sr) }
        totalSamples = 0
        startedAt = System.currentTimeMillis()
        rec.startRecording()
        recorder = rec
        thread(name = "global-recorder") {
            val buf = ShortArray(sr)  // 1s 一批
            while (recorder === rec) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    RandomAccessFile(file, "rw").use { f ->
                        f.seek(f.length())
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (buf[i].toInt() shr 8).toByte()
                        }
                        f.write(bytes)
                    }
                    totalSamples += n
                } else if (n < 0) break
            }
        }
        return true
    }

    /** 停止并回填 WAV 头。返回录音文件 */
    fun stop(): File {
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        rec?.release()
        RandomAccessFile(file, "rw").use { writeWavHeader(it, totalSamples, 16000) }
        return file
    }

    /** 44 字节标准 PCM WAV 头 */
    private fun writeWavHeader(f: RandomAccessFile, sampleCount: Int, sampleRate: Int) {
        val dataLen = sampleCount * 2
        f.seek(0)
        val head = java.io.ByteArrayOutputStream(44)
        fun le32(v: Int) { head.write(v and 0xFF); head.write((v shr 8) and 0xFF); head.write((v shr 16) and 0xFF); head.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { head.write(v and 0xFF); head.write((v shr 8) and 0xFF) }
        head.write("RIFF".toByteArray()); le32(36 + dataLen); head.write("WAVE".toByteArray())
        head.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        head.write("data".toByteArray()); le32(dataLen)
        f.write(head.toByteArray())
    }
}
