package com.retropro.feature.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 整段全局语音的研判与分析（设计方案「戴耳机录一整场」场景）。
 *
 * ## 管线
 *
 * ```
 * WAV(16k mono) → Silero VAD 切分 → 语音段/静音段（带采样点时间戳）
 *   → 每个语音段 SenseVoice token 级解码（tokens + timestamps 一一对应）
 *   → token 分类：叹气(<|Breath|>) / 语气词口头禅 / 有效语音 / 其他事件
 *   → 羽毛球热词后处理（纠正 + 命中标注）
 *   → 球序推断（比分播报 / 「第N球」直述 / 得分词 → 球事件计数）
 * ```
 *
 * ## 热词的实现方式（重要说明）
 *
 * sherpa-onnx 的 `hotwordsFile` 热词机制**只对 transducer 类模型生效**，
 * SenseVoice 是 CTC 式模型、不支持原生热词注入。所以这里用等效手段：
 * **羽毛球术语表做转写后纠正**（高置信同音错字映射）+ **命中标注**。
 * 价值等同：术语在结果里被纠正/高亮，且标注依据可查。
 *
 * ## 判断依据的原则
 *
 * 每条标注都带 `basis`（人话），标错了能查到为什么——这是全局研判
 * 与「黑盒给个列表」的区别。
 */
object GlobalAnalyzer {

    // ------------------------------------------------------------ 数据模型

    enum class SegType { SPEECH, FILLER, SIGH, OTHER_EVENT, SILENCE }

    data class Annotation(
        val startSec: Double,
        val endSec: Double,
        val type: SegType,
        val text: String,   // 转写文本（SPEECH/FILLER）或事件名/说明
        val rallyNo: Int,   // 球序。0 = 未归属任何球（比赛开始前的闲聊等）
        val basis: String,  // 判断依据
    )

    data class Report(
        val durationSec: Double,
        val items: List<Annotation>,
    ) {
        val speechSec: Double get() = items.filter { it.type == SegType.SPEECH }.sumOf { it.endSec - it.startSec }
        val silenceSec: Double get() = items.filter { it.type == SegType.SILENCE }.sumOf { it.endSec - it.startSec }
        val sighCount: Int get() = items.count { it.type == SegType.SIGH }
        val fillerCount: Int get() = items.count { it.type == SegType.FILLER }
    }

    // ------------------------------------------------------------ 羽毛球热词

    /** 热词表：转写命中后在依据里标注，也是「这段在说球」的证据之一 */
    val HOTWORDS = listOf(
        "杀球", "重杀", "点杀", "劈杀", "吊球", "挑球", "高远球", "搓球", "放网",
        "推球", "勾对角", "平抽", "抽球", "接杀", "封网", "扑球", "发球", "接发球",
        "出界", "界内", "擦网", "换球", "局点", "赛点", "得分", "失分", "球拍", "穿线", "体力",
    )

    /**
     * 高置信同音错字纠正。**只收几乎不会冤枉的映射**——
     * 宁可漏纠也不误改，热词纠正改错词比不改更糟。
     */
    private val CORRECTIONS = linkedMapOf(
        "调球" to "吊球",
        "钓球" to "吊球",
        "跳球" to "挑球",
        "条球" to "挑球",
        "沙球" to "杀球",
        "刹球" to "杀球",
        "发求" to "发球",
        "球拍" to "球拍",
    )

    // ------------------------------------------------------------ 语气词 / 口头禅

    private val FILLER_WORDS = setOf(
        "嗯", "啊", "呃", "哎", "唉", "噢", "哦", "唔", "额", "诶", "嘛", "吧", "呢",
        "那个", "就是", "然后", "这个", "其实", "反正",
    )

    private val SCORE_REGEX = Regex("(\\d+)\\s*[比:：对分]\\s*(\\d+)")
    private val ORDINAL_REGEX = Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*球")
    private val SCORE_WORDS = listOf("得分", "赢了", "拿下", "丢分", "失分", "输了")

    // ------------------------------------------------------------ VAD

    private var vad: Vad? = null

    fun ensureVadReady(context: Context): Boolean {
        if (vad != null) return true
        return synchronized(this) {
            if (vad != null) return true
            try {
                // VAD 模型同样来自插件目录（由 AsrModelPlugin 下载），不再从 assets 拷
                val dst = AsrModelPlugin.file(context, "silero_vad.onnx")
                if (!dst.isFile || dst.length() == 0L) {
                    android.util.Log.e("GlobalAnalyzer", "VAD 模型未安装：${dst.absolutePath}")
                    return@synchronized false
                }
                vad = Vad(
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = dst.absolutePath,
                            threshold = 0.5f,
                            minSilenceDuration = 0.5f,   // 静音≥0.5s 才切段：呼吸不切
                            minSpeechDuration = 0.25f,
                            windowSize = 512,
                            maxSpeechDuration = 20f,     // 超长语音强制切段，防内存膨胀
                        ),
                        sampleRate = 16000,
                        numThreads = 1,
                    ),
                )
                true
            } catch (t: Throwable) {
                android.util.Log.e("GlobalAnalyzer", "VAD init failed", t)
                false
            }
        }
    }

    /** 模型文件被删除/更换后调用，强制下次 [ensureVadReady] 重新加载 */
    fun releaseVad() {
        synchronized(this) { vad = null }
    }

    // ------------------------------------------------------------ 主入口

    fun analyze(wavFile: File): Report? {
        val samples = LocalAsr.readWavPcm16(wavFile) ?: return null
        if (samples.isEmpty()) return null
        val engine = vad ?: return null

        val sr = 16000
        val durationSec = samples.size.toDouble() / sr

        // ---- 1) VAD 切分：拿语音段（start = 采样点索引）
        val speechSegments = ArrayList<com.k2fsa.sherpa.onnx.SpeechSegment>()
        engine.reset()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + 512, samples.size)
            engine.acceptWaveform(samples.copyOfRange(offset, end))
            offset = end
            while (!engine.empty()) {
                speechSegments.add(engine.front())
                engine.pop()
            }
        }
        engine.flush()
        while (!engine.empty()) {
            speechSegments.add(engine.front())
            engine.pop()
        }

        // ---- 2) 逐段解码 + token 分类
        val items = ArrayList<Annotation>()
        var rallyCounter = 0

        var cursor = 0.0  // 秒；用于推静音段
        for (seg in speechSegments.sortedBy { it.start }) {
            val segStart = seg.start.toDouble() / sr
            val segSamples = seg.samples
            val segEnd = segStart + segSamples.size.toDouble() / sr

            // 语音段之前的静音（≥1s 才值得标）
            if (segStart - cursor >= 1.0) {
                items += Annotation(
                    startSec = cursor, endSec = segStart, type = SegType.SILENCE,
                    text = "静音",
                    rallyNo = rallyCounter,
                    basis = "Silero VAD 判定无语音（能量阈值 0.5），时长 ≥1s 才标注",
                )
            }

            val tokenResult = LocalAsr.decodeTokens(segSamples)
            if (tokenResult == null) {
                cursor = segEnd
                continue
            }

            // token 时间 → 全局时间；事件/语气词单独成条，其余拼成有效转写
            val speechText = StringBuilder()
            val hotwordHits = LinkedHashSet<String>()
            var basisExtra = ""

            val n = tokenResult.tokens.size
            for (i in 0 until n) {
                val tok = tokenResult.tokens[i]
                val tStart = segStart + (tokenResult.timestamps.getOrNull(i)?.toDouble() ?: 0.0)
                val tEnd = segStart + (tokenResult.timestamps.getOrNull(i + 1)?.toDouble()
                    ?: (segSamples.size.toDouble() / sr))
                when {
                    // SenseVoice 原生事件标签：Breath = 呼吸/叹气
                    tok == "<|Breath|>" -> items += Annotation(
                        tStart, tEnd, SegType.SIGH, "叹气", rallyCounter,
                        "SenseVoice 事件标签 <|Breath|>（模型原生声学事件检测，非关键词匹配）",
                    )
                    tok.startsWith("<|") && tok.endsWith("|>") -> items += Annotation(
                        tStart, tEnd, SegType.OTHER_EVENT, "声学事件 ${tok.removeSurrounding("<|", "|>")}",
                        rallyCounter, "SenseVoice 事件标签 $tok",
                    )
                    // 语气词 / 口头禅（token 粒度匹配）
                    tok in FILLER_WORDS -> items += Annotation(
                        tStart, tEnd, SegType.FILLER, tok, rallyCounter,
                        "命中语气词/口头禅表（无信息量，转写时保留但标注）",
                    )
                    else -> {
                        speechText.append(tok)
                    }
                }
            }

            // ---- 3) 热词后处理（整句级：同音错字纠正 + 命中标注）
            var text = speechText.toString()
            for ((wrong, right) in CORRECTIONS) {
                if (text.contains(wrong) && wrong != right) {
                    text = text.replace(wrong, right)
                    basisExtra += "；热词纠正「$wrong」→「$right」"
                }
            }
            HOTWORDS.forEach { h ->
                if (text.contains(h)) hotwordHits.add(h)
            }
            if (hotwordHits.isNotEmpty()) {
                basisExtra += "；命中羽毛球热词：${hotwordHits.joinToString("、")}"
            }

            // ---- 4) 球序推断（按事件先后，规则可查）
            var rallyBasis = "无球事件 → 归属当前球序（伴随语音）"
            if (rallyCounter == 0) rallyBasis = "尚未出现球事件 → 球序 0（比赛未开始/未记分）"
            val scoreMatch = SCORE_REGEX.find(text)
            val ordinalMatch = ORDINAL_REGEX.find(text)
            val scoreWord = SCORE_WORDS.firstOrNull { text.contains(it) }
            val isRallyEvent = scoreMatch != null || ordinalMatch != null || scoreWord != null
            if (isRallyEvent) {
                when {
                    ordinalMatch != null -> {
                        val parsed = parseCnNumber(ordinalMatch.groupValues[1])
                        rallyCounter = if (parsed > 0) parsed else rallyCounter + 1
                        rallyBasis = "直述「${ordinalMatch.value}」→ 直接采用为第 $rallyCounter 球"
                    }
                    scoreMatch != null -> {
                        rallyCounter += 1
                        rallyBasis = "比分播报「${scoreMatch.value}」→ 记一次得分事件，累计第 $rallyCounter 球"
                    }
                    else -> {
                        rallyCounter += 1
                        rallyBasis = "得分词「$scoreWord」→ 记一次得分事件，累计第 $rallyCounter 球"
                    }
                }
            }

            if (text.isNotBlank()) {
                items += Annotation(
                    startSec = segStart, endSec = segEnd, type = SegType.SPEECH,
                    text = text, rallyNo = rallyCounter,
                    basis = (if (isRallyEvent) rallyBasis else rallyBasis) + basisExtra,
                )
            } else if (basisExtra.isNotBlank()) {
                // 只有热词纠正发生（原文全是错字）——罕见，兜底输出
                items += Annotation(
                    segStart, segEnd, SegType.SPEECH, text, rallyCounter,
                    "转写为空但热词纠正生效$basisExtra",
                )
            }
            cursor = segEnd
        }

        // 尾部静音
        if (durationSec - cursor >= 1.0) {
            items += Annotation(
                cursor, durationSec, SegType.SILENCE, "静音", rallyCounter,
                "Silero VAD 判定无语音（结尾段）",
            )
        }

        return Report(durationSec = durationSec, items = items)
    }

    /** 「二十一」/「5」→ Int。解析失败返回 0 */
    private fun parseCnNumber(s: String): Int {
        s.trim().toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        var result = 0
        var current = 0
        for (c in s) {
            when (c) {
                '十' -> { result += (if (current == 0) 1 else current) * 10; current = 0 }
                '百' -> { result += (if (current == 0) 1 else current) * 100; current = 0 }
                in '0'..'9' -> { result = result * 10 + (c - '0'); continue }
                else -> digits[c]?.let { current = it }
            }
        }
        return result + current
    }
}
