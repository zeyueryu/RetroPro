package com.retropro.feature.voice

/**
 * 从一句话里抽出来的结构化草稿。
 *
 * ## 最重要的原则：低置信度宁可不填
 *
 * 每个字段要么是**明确抽到的**，要么是 `null`。
 * 抽错的数据比没抽到更糟 —— 用户扫一眼表单时不会逐字段核对，
 * 一个填错的费用会被原样存进长期日记，污染统计。
 *
 * 所以这里只做"闭集"抽取：时长、费用、场馆三种，各自有明确的单位锚点
 * （小时/分钟、块/元、馆）。没有单位锚点的数字一律不猜。
 */
data class VoiceDraft(
    /** 时长（分钟） */
    val durationMin: Int? = null,
    /** 费用（元） */
    val feeYuan: Double? = null,
    /** 场馆名 */
    val venue: String? = null,
    /** 识别原文，用于让用户核对 */
    val raw: String = "",
) {
    val isEmpty: Boolean
        get() = durationMin == null && feeYuan == null && venue == null

    /** 抽到的字段名，UI 用来高亮"这几个是语音填的" */
    val filledFields: List<String>
        get() = buildList {
            if (durationMin != null) add("时长")
            if (feeYuan != null) add("费用")
            if (venue != null) add("场馆")
        }
}

/**
 * 中文自由文本 → [VoiceDraft]。
 *
 * ## 为什么不用 ML Kit Entity Extraction
 *
 * 设计方案 §7.2 原本写的是 ML Kit。实际评估后换成规则抽取，理由有三：
 *
 *  1. **ML Kit 的 Entity Extraction 以英文语料训练**，"打了两小时花了四十块"这种
 *     中文表达基本抓不到 —— 而中文数字恰恰是我们最常说的形式。
 *  2. 它是**按需下载**的模型（首次调用要联网下几 MB），不符合"离线优先"的基调。
 *  3. 我们的域**极窄**（只有时长 / 费用 / 场馆三种），规则抽取在这个封闭集合上
 *     比通用模型更准，而且可测、可解释、零依赖。
 *
 * ## 做法：先归一化，再按单位锚点抽取
 *
 * 中文数字（两 / 二十五 / 一个半）先统一转成阿拉伯数字，之后正则只需要处理一种形式。
 * 每个字段都必须命中**单位锚点**才会被填 —— 见 [VoiceDraft] 的注释。
 */
object VoiceParser {

    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '两' to 2, '贰' to 2,
        '三' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9,
    )

    private const val CN_UNIT_CHARS = "零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十百千"

    /** 中文数字串（十 / 十五 / 二十 / 二十五 / 一百二十）→ Int */
    private fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null

        // 纯数字串，如 "二三" → 23（口语里少见，但"二三十分钟"这种存在）
        if (s.all { it in CN_DIGIT }) {
            return s.mapNotNull { CN_DIGIT[it] }.joinToString("").toIntOrNull()
        }

        // 带权位：数字只记在 number 上，遇到权位才结算；循环结束后再补末尾的个位
        var section = 0
        var number = 0
        for (ch in s) {
            when (ch) {
                '十' -> {
                    section += (if (number == 0) 1 else number) * 10
                    number = 0
                }

                '百' -> {
                    section += (if (number == 0) 1 else number) * 100
                    number = 0
                }

                '千' -> {
                    section += (if (number == 0) 1 else number) * 1000
                    number = 0
                }

                else -> number = CN_DIGIT[ch] ?: return null
            }
        }
        section += number
        return section.takeIf { it > 0 }
    }

    /** 把文本里的中文数字串替换成阿拉伯数字。其余字符原样保留。 */
    private fun normalizeNumerals(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] in CN_UNIT_CHARS) {
                var j = i
                while (j < text.length && text[j] in CN_UNIT_CHARS) j++
                val chunk = text.substring(i, j)
                val value = cnToInt(chunk)
                if (value != null) sb.append(value) else sb.append(chunk)
                i = j
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }

    private val HOUR_MIN = Regex("""(\d+)\s*(?:个)?\s*(?:小时|钟头)\s*(\d+)?\s*(?:分钟|分)?""")
    private val HALF_HOUR_WITH_LEAD = Regex("""(\d+)\s*个\s*半\s*(?:个)?\s*(?:小时|钟头)""")
    private val HALF_HOUR = Regex("""半\s*(?:个)?\s*(?:小时|钟头)""")
    private val MINUTES = Regex("""(\d+)\s*(?:分钟|分)""")

    private val FEE_WITH_UNIT = Regex("""(\d+(?:\.\d+)?)\s*(?:块钱|元钱|块|元)""")
    private val FEE_SPENT = Regex("""花(?:了|掉)?\s*(\d+(?:\.\d+)?)""")

    /**
     * 场馆。分两步，且**顺序有意义**：
     *
     * 1. [VENUE_AFTER_PREFIX]：有方位/动词前缀时，名字紧跟在它后面。这一步最可靠。
     * 2. [VENUE_ONLY]：没有前缀时只认**句首**的场馆名。
     *
     * 为什么第 2 步必须锚定 `^`：早期写成不带锚的"裸名字"规则，结果
     * 「今天在城东体育馆打了两小时」被抽成「今天在城东体育馆」——
     * 无锚的正则可以从任意位置起匹配，把前面的时间状语一起吞进名字里。
     *
     * 非贪婪的 `{2,10}?` 保证取最短可行长度：「奥体中心球馆」→「奥体中心球馆」
     * 而不是「奥体」+ 落空。
     */
    private val VENUE_AFTER_PREFIX = Regex(
        """(?:在|去了|去|到|和|跟|约了)\s*([\u4e00-\u9fa5]{2,10}?(?:体育馆|羽毛球馆|球馆|俱乐部|运动中心))"""
    )
    private val VENUE_ONLY = Regex(
        """^([\u4e00-\u9fa5]{2,10}?(?:体育馆|羽毛球馆|球馆|俱乐部|运动中心))"""
    )

    /** 场馆名前面可能粘上的时间状语 / 方位词。抽到后要剥掉，否则会一起存进库。 */
    private val VENUE_LEADING_NOISE = Regex(
        """^(?:今天|昨天|前天|早上|上午|中午|下午|晚上|刚刚|刚才|然后|后来|又|去|在)+"""
    )

    fun parse(input: String): VoiceDraft {
        val raw = input.trim()
        if (raw.isEmpty()) return VoiceDraft(raw = raw)
        val text = normalizeNumerals(raw)

        return VoiceDraft(
            durationMin = extractDuration(text),
            feeYuan = extractFee(text),
            venue = extractVenue(text),
            raw = raw,
        )
    }

    /**
     * 时长。按优先级匹配，避免"1小时40分钟"被 MINUTES 单独抓成 40。
     */
    private fun extractDuration(text: String): Int? {
        // "一个半小时" / "两个小时" 中的半
        HALF_HOUR_WITH_LEAD.find(text)?.let { m ->
            val whole = m.groupValues[1].toIntOrNull() ?: return@let
            return whole * 60 + 30
        }
        // 光说"半小时"
        if (HALF_HOUR.containsMatchIn(text) && !Regex("""\d""").containsMatchIn(text)) {
            return 30
        }
        HOUR_MIN.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + min
        }
        MINUTES.find(text)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    /** 费用。带单位更可信；"花了X"次之。 */
    private fun extractFee(text: String): Double? {
        FEE_WITH_UNIT.find(text)?.let { m ->
            return m.groupValues[1].toDoubleOrNull()
        }
        FEE_SPENT.find(text)?.let { m ->
            return m.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    /**
     * 场馆。只在有前缀或位于句首时才认 —— 见 [VENUE_AFTER_PREFIX] 的注释。
     *
     * 剥掉前缀噪声后还要求名字主体至少 2 个字（含后缀共 ≥4），
     * 否则宁可返回 null 让用户自己填 —— 抽错比不抽更糟。
     */
    private fun extractVenue(text: String): String? {
        val raw = (VENUE_AFTER_PREFIX.find(text) ?: VENUE_ONLY.find(text))
            ?.groupValues?.get(1)
            ?: return null
        val cleaned = raw.replace(VENUE_LEADING_NOISE, "")
        return cleaned.takeIf { it.length >= 4 }
    }

    /** 给 UI 用的一句提示 */
    fun summary(draft: VoiceDraft): String = when {
        draft.raw.isEmpty() -> "没有听清"
        draft.isEmpty -> "听到了「${draft.raw}」，但没识别出可填的字段，请手动填写"
        else -> "已识别：${draft.filledFields.joinToString("、")}　·　请核对后再保存"
    }
}
