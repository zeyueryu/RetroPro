package com.retropro.uikit.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 项目色彩规范。
 *
 * ## 本轮改版：OLED 纯黑 → MIUIX 白
 *
 * 早期版本是 OLED 纯黑 + 液态玻璃。现在按需求切到 **MIUIX 白色背景**。
 * 实现方式是**双调色板 + 一个模式开关**，而不是把黑色版删掉重写：
 * [isDark] 为 `true` 时整套色值自动切回黑底方案，一行开关即可来回。
 * 之所以保留：暗光环境（球馆晚上灯光偏暗）仍有使用价值，
 * 而且液态玻璃在纯黑和纯白上的表现差异很大，留着便于对比。
 *
 * ## 玻璃在浅色底上的两个关键差异（不是把颜色反过来那么简单）
 *
 * 1. **暗化层要反过来变亮**。深色底上用 30% 黑垫在玻璃下面来保证文字对比度；
 *    白底上如果继续垫黑，面板会变成灰块、失去"玻璃"感。
 *    白底要垫 **55% 白** —— 把面板托到背景之上，文字才有对比度。
 * 2. **折射对象要换**。黑底靠 10% 白色细条纹提供可折射的结构；
 *    白底上白条纹完全看不见，必须换成 **7% 黑色细条纹**。
 *    光晕同理，要降低不透明度，否则在白底上会糊成一片脏色。
 *
 * ## 语义色不随模式变
 *
 * HyperOS 蓝、对方橙、成功绿是**品牌与语义**，两个模式下保持同一色相。
 * 但它们在两种底上的可读性不同，所以 `MeSoft` / `OpponentSoft` 这类
 * "用于文字"的变体在白底上加深、在黑底上提亮。
 */
object AppColors {

    /**
     * 是否暗色（OLED 纯黑）模式。
     *
     * 用 `mutableStateOf` 而非普通 `var`：读它的组合作用域会在模式切换时自动重组，
     * 不需要把主题参数一层层传下去。`AppColors.xxx` 的所有调用点因此都无需改动。
     *
     * ⚠️ 正因为是动态值，**不能在顶层用 `val X = AppColors.Y` 缓存它**
     * （那会在文件首次加载时固化）。必须在组合内读取，或在需要处写成 getter。
     */
    /**
     * 主题意图（来自设置页/系统跟随）。**计分板 AMOLED 不要写这里** ——
     * 它会被 MainActivity 的「应用偏好」随时覆盖；覆写请用 [forceDark]。
     */
    var isDarkIntent: Boolean by mutableStateOf(false)

    /** 局部覆写：计分板 AMOLED 纯黑模式进入时置 true，退出自动清零 */
    var forceDark: Boolean by mutableStateOf(false)

    /**
     * 当前是否暗色（OLED 纯黑）模式 = 意图 OR 覆写。
     *
     * 用 `mutableStateOf` 而非普通 `var`：读它的组合作用域会在模式切换时自动重组，
     * 不需要把主题参数一层层传下去。`AppColors.xxx` 的所有调用点因此都无需改动。
     *
     * ⚠️ 正因为是动态值，**不能在顶层用 `val X = AppColors.Y` 缓存它**
     * （那会在文件首次加载时固化）。必须在组合内读取，或在需要处写成 getter。
     */
    val isDark: Boolean
        get() = isDarkIntent || forceDark

    // ---------------------------------------------------------------- 基底

    /** 页面基底。浅色 = MIUIX 白；暗色 = OLED 纯黑 */
    val Background: Color
        get() = if (isDark) Color(0xFF000000) else Color(0xFFF7F7F7)

    /** 玻璃层被降级/关闭时的纯色卡片底色 */
    val SurfaceFallback: Color
        get() = if (isDark) Color(0xFF12141A) else Color(0xFFFFFFFF)

    /** 玻璃层被降级时的强调卡片底色 */
    val SurfaceFallbackAccent: Color
        get() = if (isDark) Color(0xFF152A4D) else Color(0xFFE3EDFF)

    // ---------------------------------------------------------------- 玻璃

    /**
     * 玻璃面板的暗化层。
     *
     * 深色底：30% 黑（把面板压下去，保证浅色文字可读）。
     * 浅色底：55% 白（把面板托起来，保证深色文字可读）。
     * 两个数值都是实测折中：低于下界文字读不清，高于上界玻璃感消失。
     */
    val GlassScrim: Color
        get() = if (isDark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.55f)

    /** 玻璃面板的发丝描边 */
    val GlassLine: Color
        get() = if (isDark) Color(0x1AFFFFFF) else Color(0x14000000)

    /** 玻璃下方垫在折射内容前的底色（Haze 的 backgroundColor 用） */
    val GlassBackdropFill: Color
        get() = if (isDark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.55f)

    // ---------------------------------------------------------------- 语义色

    /** HyperOS 蓝 —— 我方 / 主强调 */
    val Me: Color get() = Color(0xFF3482FF)

    /** 用于文字/图标的蓝：白底上加深，黑底上提亮 */
    val MeSoft: Color get() = if (isDark) Color(0xFF7FB0FF) else Color(0xFF1B5FD9)

    val MeDim: Color get() = if (isDark) Color(0x293482FF) else Color(0x1F3482FF)

    /** 对方 / 失分 */
    val Opponent: Color get() = Color(0xFFFF6A3D)

    val OpponentSoft: Color get() = if (isDark) Color(0xFFFF9B78) else Color(0xFFC9411A)

    val OpponentDim: Color get() = if (isDark) Color(0x29FF6A3D) else Color(0x1FFF6A3D)

    /** 胜利 / 已复盘 */
    val Success: Color get() = Color(0xFF34C759)

    val SuccessSoft: Color get() = if (isDark) Color(0xFF5BD97C) else Color(0xFF1B8F3A)

    // ---------------------------------------------------------------- 文字

    /**
     * 自定义文字色（`null` = 用默认）。
     *
     * ## 为什么要做成可调
     *
     * 默认的三档灰在 MIUIX 白底上对比度是够的，但**用户各自的眼睛与屏幕差异很大** ——
     * 有人觉得 `TextPrimary` 的 #1A1A1A 太黑刺眼，有人觉得 `TextTertiary` 的 #9A9A9A 读不清。
     * 这是个人自用 App，让用户自己调比争论"标准对比度是多少"更实际。
     *
     * ## 与 [isDark] 的关系
     *
     * 自定义值是**单一值，不区分明暗模式**。理由：用户调的是"我要什么样的字色"，
     * 而不是"浅色下什么色、深色下什么色"—— 后者是设计系统该操心的事，
     * 让用户各调两套反而负担。切换明暗模式时自定义色保持不变。
     *
     * ⚠️ 和 [isDarkIntent] 一样是**动态值**，读它的 getter 会随改动自动重组。
     * 同理**不能顶层缓存**。
     *
     * 持久化在 `AppPrefs`（存 ARGB 的 Int，`0` 作为"未设置"哨兵因为纯透明没有实际用途）。
     */
    var customTextPrimary: Color? by mutableStateOf(null)

    var customTextSecondary: Color? by mutableStateOf(null)

    var customTextTertiary: Color? by mutableStateOf(null)

    val TextPrimary: Color
        get() = customTextPrimary ?: if (isDark) Color(0xFFF5F6F8) else Color(0xFF1A1A1A)

    val TextSecondary: Color
        get() = customTextSecondary ?: if (isDark) Color(0xFF9AA0A8) else Color(0xFF6B6B6B)

    val TextTertiary: Color
        get() = customTextTertiary ?: if (isDark) Color(0xFF5E646C) else Color(0xFF9A9A9A)

    /** 清空全部自定义文字色，回到主题默认 */
    fun resetTextColors() {
        customTextPrimary = null
        customTextSecondary = null
        customTextTertiary = null
    }

    /** 是否有任何一档被自定义过（设置页用来决定要不要显示「重置」） */
    val hasCustomTextColor: Boolean
        get() = customTextPrimary != null || customTextSecondary != null || customTextTertiary != null

    /** 中性色块的填充（chip 未选中态等） */
    val NeutralFill: Color get() = if (isDark) Color(0x14FFFFFF) else Color(0x0F000000)

    val NeutralFillDisabled: Color get() = if (isDark) Color(0x08FFFFFF) else Color(0x06000000)

    /** 主按钮上的文字。蓝底永远用白字，两种模式一致 */
    val OnAccent: Color get() = Color.White

    // ---------------------------------------------------------------- 背景（折射对象）

    /**
     * 背景细条纹。
     *
     * 这是"折射标尺"—— 直线在玻璃边缘弯折是折射唯一的铁证。
     * ⚠️ 颜色必须随模式反转：白底上用白色条纹等于没画。
     */
    val Stripe: Color
        get() = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)

    /** 背景光晕。白底上不透明度要压到约一半，否则会糊成脏色 */
    val GlowBlue: Color
        get() = if (isDark) Color(0x4D3482FF) else Color(0x303482FF)

    val GlowTeal: Color
        get() = if (isDark) Color(0x2900CDB4) else Color(0x2200CDB4)

    val GlowOrange: Color
        get() = if (isDark) Color(0x24FF6A3D) else Color(0x1EFF6A3D)

    /** 背景描边圆环 */
    val RingCool: Color get() = MeSoft
    val RingWarm: Color get() = OpponentSoft
}
