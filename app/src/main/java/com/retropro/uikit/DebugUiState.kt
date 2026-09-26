package com.retropro.uikit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全局 UI 调试状态 —— 调试面板（DebugPanel）的数据源。
 *
 * ## 对应关系（按用户熟悉的框架说明）
 *
 * 这就是 Compose 版的"全局状态管理"：单例 + `mutableStateOf`，
 * 等价于 React 的 Context/Redux 或 Flutter 的 Provider——
 * 所有读取它的组合在值变化时自动重组，**无刷新实时更新**。
 *
 * ## 绑定点
 *
 * | 参数 | 绑定位置 |
 * |---|---|
 * | borderRadius | GlassPanel 的 shape（覆盖 AppShapes.Card/Dock/Sheet 等全部玻璃卡） |
 * | cardOpacity | GlassPanel 整卡 alpha |
 * | refractionIntensity | RefractionSpec 的 blur / refractionAmount 按倍率缩放（透光高光随模糊一同衰减） |
 *
 * 默认值：圆角 26dp（= AppShapes.Card）、透明度 1.0、折射 1.0（= RefractionSpec.Standard 原样）。
 * 「重置为默认值」即回到这三个数。
 */
object DebugUiState {

    const val DEFAULT_RADIUS = 26f
    const val DEFAULT_OPACITY = 1f
    const val DEFAULT_REFRACTION = 1f

    /** 面板显示开关：debug 构建里连点版本号 5 次切换 */
    var enabled by mutableStateOf(false)

    /** 全局圆角（dp），0–50 */
    var borderRadius by mutableFloatStateOf(DEFAULT_RADIUS)

    /** 卡片整体透明度，0–1 */
    var cardOpacity by mutableFloatStateOf(DEFAULT_OPACITY)

    /** 折射强度倍率，0–2（1 = 标准玻璃效果） */
    var refractionIntensity by mutableFloatStateOf(DEFAULT_REFRACTION)

    fun reset() {
        borderRadius = DEFAULT_RADIUS
        cardOpacity = DEFAULT_OPACITY
        refractionIntensity = DEFAULT_REFRACTION
    }

    /** 调好的参数以 JSON 输出，可直接写回正式代码 */
    fun toJson(): String =
        """{"borderRadiusDp": ${borderRadius.round1()}, """ +
            """"cardOpacity": ${cardOpacity.round1()}, """ +
            """"refractionIntensity": ${refractionIntensity.round1()}}"""

    fun toCssSnippet(): String =
        ":root {\n  --card-radius: ${borderRadius.round1()}px;\n  --card-opacity: ${cardOpacity.round1()};\n  --refraction-intensity: ${refractionIntensity.round1()};\n}"

    private fun Float.round1(): String = "%.1f".format(this)

    /** 调试生效时的卡片圆角；未启用返回 null（调用方使用原 shape） */
    fun debugShapeOrNull(fallback: RoundedCornerShape): RoundedCornerShape? =
        if (enabled) RoundedCornerShape(borderRadius.dp) else null
}
