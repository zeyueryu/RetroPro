package com.retropro.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 一次语音录入的状态机 */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** 正在录音（本地引擎，离线）。`seconds` 是已录秒数 —— SenseVoice 是离线模型，没有中间结果 */
    data class Listening(val seconds: Int) : VoiceState

    /** 录完了，本地引擎解码中（大模型解码要几秒，必须有这个态，否则像卡死） */
    data object Recognizing : VoiceState

    /** 拿到最终文本并解析出草稿 */
    data class Done(val draft: VoiceDraft) : VoiceState

    /** 失败。message 直接展示给用户，不用错误码 */
    data class Failed(val message: String) : VoiceState
}

/**
 * 语音识别控制器 —— **本地引擎优先**（SenseVoice-Small，离线），系统识别服务兜底。
 *
 * ## 为什么要本地引擎
 *
 * 设计方案的核心是离线优先。原方案走系统 [SpeechRecognizer]（联网），
 * 是当时最快的打通路径；现在接入 sherpa-onnx + SenseVoice-Small int8
 * （模型来自魔搭镜像，打包进 assets，见 [LocalAsr]）：
 *  - 完全离线，球场地下室/飞行模式都能用
 *  - 没有识别服务的 ROM 也能用（模型在 APK 里，不依赖系统服务）
 *  - 中文数字 ITN 内置（「两小时」→「2小时」，解析器更稳）
 *
 * 系统识别保留为兜底：本地初始化失败（assets 异常等）时自动降级。
 *
 * ## 两条不变的设计
 *
 * 1. **识别结果必须经用户确认才落库**（§7.2 强制）——本地识别也一样会错。
 * 2. 生命周期坑集中在这一层处理，UI 只看 [state]。
 */
class VoiceInputController(private val context: Context) {

    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var ticker: Job? = null

    /** 本地引擎是否就绪（lazy：第一次打开面板才拷模型/建引擎，不拖慢 App 启动） */
    val localReady: Boolean by lazy { LocalAsr.ensureReady(context) }

    /** 设备是否有可用的语音识别（本地或系统） */
    fun isAvailable(): Boolean = localReady || SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        state = VoiceState.Idle
        if (localReady) {
            if (!LocalAsr.startRecording()) {
                state = VoiceState.Failed("无法启动麦克风，请检查录音权限")
                return
            }
            state = VoiceState.Listening(seconds = 0)
            ticker = scope.launch {
                while (true) {
                    delay(1_000)
                    val cur = state
                    if (cur is VoiceState.Listening) {
                        val sec = LocalAsr.elapsedSeconds()
                        state = VoiceState.Listening(seconds = sec)
                        // 一句话足够；60s 自动停，防止忘了点停止白耗电
                        if (sec >= 60) stop()
                    } else {
                        break
                    }
                }
            }
            return
        }

        // ---- 兜底：系统识别服务（联网）
        if (!isAvailable()) {
            state = VoiceState.Failed("这台设备没有可用的语音识别服务，请用下方文字输入")
            return
        }

        // 复用实例而不是每次新建：SpeechRecognizer.createSpeechRecognizer 成本不低，
        // 且频繁创建/销毁容易触发 ERROR_RECOGNIZER_BUSY
        val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        state = VoiceState.Listening(seconds = 0)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 中文普通话。我们的解析器只处理中文数字与中文场馆名
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 一句话就够：用户说的是"在城东体育馆打了两小时花了四十块"
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        runCatching { engine.startListening(intent) }
            .onFailure { state = VoiceState.Failed("无法启动录音：${it.message}") }
    }

    /** 用户主动结束（点"停止"）。本地引擎：停止录音 → 解码 → 解析 */
    fun stop() {
        if (localReady && LocalAsr.isRecording) {
            ticker?.cancel()
            ticker = null
            state = VoiceState.Recognizing
            scope.launch {
                // 解码在 Default 线程池：228MB int8 模型推理要几秒，不能卡主线程
                val text = withContext(Dispatchers.Default) { LocalAsr.stopAndDecode() }
                finishWith(text)
            }
            return
        }
        recognizer?.stopListening()
    }

    private fun finishWith(text: String) {
        val t = text.trim()
        state = if (t.isBlank()) {
            VoiceState.Failed("没有听清，可以再说一次，或直接用文字输入")
        } else {
            VoiceState.Done(VoiceParser.parse(t))
        }
    }

    /**
     * 直接用文字走同一个解析器。
     *
     * 这不是"降级方案"，而是**并列的正常入口**：敲字比说话安静、可编辑，
     * 在球场边（嘈杂）或不方便说话的场合更实用。
     */
    fun submitText(text: String) {
        state = if (text.isBlank()) {
            VoiceState.Idle
        } else {
            VoiceState.Done(VoiceParser.parse(text))
        }
    }

    fun reset() {
        state = VoiceState.Idle
    }

    fun destroy() {
        ticker?.cancel()
        ticker = null
        recognizer?.let { engine ->
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }
        recognizer = null
        state = VoiceState.Idle
    }

    private val listener = object : RecognitionListener {

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            finishWith(text)
        }

        override fun onError(error: Int) {
            state = VoiceState.Failed(describe(error))
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit  // 本地引擎没有中间结果；系统引擎也不展示

        // 以下回调在我们的流程里不需要处理
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 把错误码翻译成用户能据此行动的一句话 */
    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "没有听清，可以再说一次，或直接用文字输入"

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "没有录音权限，请在系统设置里允许本应用使用麦克风"

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "网络不可用。语音识别需要联网，可以改用文字输入"

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "识别服务正忙，请稍后重试"

        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请重试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务返回错误，请重试"
        else -> "识别失败（错误码 $error），可以改用文字输入"
    }
}
