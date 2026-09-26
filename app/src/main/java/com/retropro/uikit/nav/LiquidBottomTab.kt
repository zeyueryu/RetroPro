package com.retropro.uikit.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 胶囊形状 = 半径 50%。等价于官方示例用的 `com.kyant.shapes.Capsule()`，
 * 但不需要额外引 `kyant-shapes`（它在 Backdrop 的 POM 里只是 runtime 作用域）。
 */
internal val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * 按压时给 Tab 内容施加的缩放倍率。由导航栏在按压进度变化时下发。
 * 移植自 Kyant0 `AndroidLiquidGlass` `components/LiquidBottomTab.kt`。
 */
internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/**
 * 单个导航项容器。移植自官方同文件，只把 `Capsule()` 换成 [CapsuleShape]。
 *
 * 注意 `.fillMaxHeight() + .weight(1f)`：高度铺满导航条，宽度均分，
 * 这样按压时的放大是围绕各自的单元格进行的。
 */
@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(CapsuleShape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
