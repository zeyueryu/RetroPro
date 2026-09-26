package com.retropro.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 外观偏好（SharedPreferences，个人 App 不值得为几个布尔值引 DataStore）。
 *
 * 所有值都是「启动时读一次 + 改动时写」，不监听变化——
 * 需要即时生效的开关（isDark 等）由 UI 层写内存状态（AppColors.isDark），
 * 这里只负责落盘，App 下次启动恢复。
 */
object AppPrefs {

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    enum class BackgroundStyle { DEFAULT, MINIMAL, SOLID, IMAGE }

    /** 录音按钮可选底色（语义色不动，只动这个按钮） */
    val RecordButtonColors: List<Pair<String, Color>> = listOf(
        "HyperOS 蓝" to Color(0xFF3482FF),
        "成功绿" to Color(0xFF34C759),
        "对方橙" to Color(0xFFFF6A3D),
        "紫" to Color(0xFF8A5CF6),
        "粉" to Color(0xFFFF5C8A),
    )

    private const val FILE = "app_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 主题

    fun themeMode(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs(context).getString("themeMode", null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString("themeMode", mode.name).apply()
    }

    fun scoreboardAmoled(context: Context): Boolean = prefs(context).getBoolean("scoreboardAmoled", false)

    fun setScoreboardAmoled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("scoreboardAmoled", on).apply()
    }

    // ------------------------------------------------------------ 录音按钮

    /** 存的是 RecordButtonColors 的下标 */
    fun recordButtonIndex(context: Context): Int = prefs(context).getInt("recordBtnIdx", 0)

    fun setRecordButtonIndex(context: Context, index: Int) {
        prefs(context).edit().putInt("recordBtnIdx", index).apply()
    }

    fun recordButtonColor(context: Context): Color =
        RecordButtonColors[recordButtonIndex(context).coerceIn(0, RecordButtonColors.lastIndex)].second

    // ------------------------------------------------------------ 背景

    fun backgroundStyle(context: Context): BackgroundStyle =
        runCatching { BackgroundStyle.valueOf(prefs(context).getString("bgStyle", null) ?: "DEFAULT") }
            .getOrDefault(BackgroundStyle.DEFAULT)

    fun setBackgroundStyle(context: Context, style: BackgroundStyle) {
        prefs(context).edit().putString("bgStyle", style.name).apply()
    }

    /** 自定义背景图（Photo Picker 拷贝到 filesDir，避免 uri 授权过期） */
    fun backgroundImageFile(context: Context) =
        java.io.File(context.filesDir, "custom_bg.jpg")

    /**
     * 自定义图片背景的遮罩浓度（0–1，默认 0.40）。
     * 固定 0.6 的白纱会把整屏蒙雾——浓度交给用户在「外观」里调。
     */
    fun bgMaskOpacity(context: Context): Float =
        prefs(context).getFloat("bgMaskOpacity", 0.40f)

    fun setBgMaskOpacity(context: Context, v: Float) {
        prefs(context).edit().putFloat("bgMaskOpacity", v).apply()
    }

    // ------------------------------------------------------------ 每日格言

    /**
     * 自定义格言（null/空 = 没设，走预设轮换）。
     * 显示逻辑收在 [DailyQuotes.forToday]，这里只管存取。
     */
    fun dailyQuote(context: Context): String? =
        prefs(context).getString("dailyQuote", null)?.takeIf { it.isNotBlank() }

    fun setDailyQuote(context: Context, value: String?) {
        prefs(context).edit().putString("dailyQuote", value?.trim()?.takeIf { it.isNotBlank() }).apply()
    }

    // ------------------------------------------------------------ 字体颜色

    /**
     * 自定义文字色的预设色板（三档共用）。
     *
     * 选色思路：**不是随便挑好看的色**，而是覆盖"用户为什么想改字色"的几种诉求 ——
     *  - 中立档（近黑 / 深灰 / 中灰）给"默认还是太黑/太浅，往中间挪一点"
     *  - 冷色档（深蓝 / 靛青）给"想要一点科技感又不想太跳"
     *  - 暖色档（深棕 / 酒红）给"白底上刺眼，换成暖色柔和些"
     *  - 语义档（HyperOS 蓝 / 对方橙）给"我就要整页是主题色"的极端偏好
     *
     * 每档都配了浅/深两个版本？**没有** —— 见 [AppColors.customTextPrimary] 的说明，
     * 自定义值是单一值，不随明暗模式变。所以每个色都选了**在白底和黑底上都能读**的中间明度，
     * 而不是纯黑或纯白（那两个在另一种底上会直接消失）。
     */
    val TextColorPresets: List<Pair<String, Color>> = listOf(
        "墨黑" to Color(0xFF1A1A1A),
        "深灰" to Color(0xFF4A4A4A),
        "中灰" to Color(0xFF6B6B6B),
        "深蓝" to Color(0xFF1B3A6B),
        "靛青" to Color(0xFF2A5C8A),
        "深棕" to Color(0xFF5A3E2B),
        "酒红" to Color(0xFF7A2E3A),
        "松绿" to Color(0xFF2E5A45),
        "紫" to Color(0xFF5B3A8A),
        "HyperOS 蓝" to Color(0xFF3482FF),
    )

    private const val KEY_TEXT_PRIMARY = "textColorPrimary"
    private const val KEY_TEXT_SECONDARY = "textColorSecondary"
    private const val KEY_TEXT_TERTIARY = "textColorTertiary"

    /**
     * 存 ARGB 的 Long。**`0L` 是"未设置"哨兵** —— 纯透明（alpha=0）作为文字色没有实际用途，
     * 拿它当哨兵比另外存一个布尔标记更省事。
     */
    private fun readColor(context: Context, key: String): Color? {
        val raw = prefs(context).getLong(key, 0L)
        return if (raw == 0L) null else Color(raw.toInt())
    }

    private fun writeColor(context: Context, key: String, color: Color?) {
        prefs(context).edit().putLong(key, color?.toArgb()?.toLong() ?: 0L).apply()
    }

    fun textPrimary(context: Context) = readColor(context, KEY_TEXT_PRIMARY)

    fun setTextPrimary(context: Context, color: Color?) {
        writeColor(context, KEY_TEXT_PRIMARY, color)
        com.retropro.uikit.theme.AppColors.customTextPrimary = color
    }

    fun textSecondary(context: Context) = readColor(context, KEY_TEXT_SECONDARY)

    fun setTextSecondary(context: Context, color: Color?) {
        writeColor(context, KEY_TEXT_SECONDARY, color)
        com.retropro.uikit.theme.AppColors.customTextSecondary = color
    }

    fun textTertiary(context: Context) = readColor(context, KEY_TEXT_TERTIARY)

    fun setTextTertiary(context: Context, color: Color?) {
        writeColor(context, KEY_TEXT_TERTIARY, color)
        com.retropro.uikit.theme.AppColors.customTextTertiary = color
    }

    /** 清空三档自定义字色（回到主题默认） */
    fun resetTextColors(context: Context) {
        writeColor(context, KEY_TEXT_PRIMARY, null)
        writeColor(context, KEY_TEXT_SECONDARY, null)
        writeColor(context, KEY_TEXT_TERTIARY, null)
        com.retropro.uikit.theme.AppColors.resetTextColors()
    }

    // ------------------------------------------------------------ 启动

    /**
     * 进程启动时应用一次。SYSTEM 模式交给 UI 层用 isSystemInDarkTheme() 计算
     * （这里拿不到 configuration）。
     */
    fun applyThemeAtStartup(context: Context, systemDark: Boolean) {
        com.retropro.uikit.theme.AppColors.isDarkIntent = when (themeMode(context)) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }
        // 自定义字色：必须在这里恢复（Activity 重建 / 进程重启后 AppColors 的
        // mutableStateOf 会回到 null），否则用户设的字色看起来"自己丢了"
        com.retropro.uikit.theme.AppColors.customTextPrimary = textPrimary(context)
        com.retropro.uikit.theme.AppColors.customTextSecondary = textSecondary(context)
        com.retropro.uikit.theme.AppColors.customTextTertiary = textTertiary(context)
        Live.load(context)
    }

    /**
     * 运行时外观状态 —— 仿 `AppColors.isDark` 的模式：`mutableStateOf` 让读取它的
     * 组合在改动时自动重组，设置页改完立即生效；[AppPrefs.setXxx] 同时落盘。
     */
    object Live {
        var backgroundStyle: BackgroundStyle by androidx.compose.runtime.mutableStateOf(BackgroundStyle.DEFAULT)
        var bgMaskOpacity: Float by androidx.compose.runtime.mutableFloatStateOf(0.40f)
        var recordButtonIndex: Int by androidx.compose.runtime.mutableIntStateOf(0)
        var scoreboardAmoled: Boolean by androidx.compose.runtime.mutableStateOf(false)
        var dailyQuote: String? by androidx.compose.runtime.mutableStateOf<String?>(null)

        fun load(context: Context) {
            backgroundStyle = backgroundStyleOf(context)
            bgMaskOpacity = bgMaskOpacity(context)
            recordButtonIndex = recordButtonIndexOf(context)
            scoreboardAmoled = scoreboardAmoledOf(context)
            dailyQuote = dailyQuote(context)
        }

        private fun backgroundStyleOf(context: Context) = backgroundStyle(context)
        private fun recordButtonIndexOf(context: Context) = recordButtonIndex(context)
        private fun scoreboardAmoledOf(context: Context) = scoreboardAmoled(context)
    }
}
