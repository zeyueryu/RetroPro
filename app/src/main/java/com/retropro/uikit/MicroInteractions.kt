package com.retropro.uikit

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 微动效果（micro-interaction）—— 按压缩放 + 轻触感。
 *
 * ## 参照的成熟方案
 *
 * 1. **Material 3 Expressive 的 press-scale**（Android 官方动效规范）：
 *    按下缩到 0.95 左右、松开用偏弹的 spring 回弹 —— 界面「有实体感」的核心一招。
 * 2. **MIUIX / HyperOS 交互语言**（compose-miuix-ui/miuix 源码核实）：
 *    官方组件用 `interactionSource + indication` 立反馈点；HyperOS 真机的表达更克制 ——
 *    无大面积涟漪，以轻微缩放为主。
 *
 * 因此这里：缩放幅度小（0.96）、spring 快而微弹、涟漪关闭（由调用方传 `indication = null`）、
 * 按下瞬间一次轻触感。
 *
 * ## 性能红线（务必遵守）
 *
 * - 动画值**只在 `graphicsLayer { }`（绘制阶段）的 lambda 里读**，
 *   不进组合期 —— 按压动画不触发任何重组。
 * - `interactionSource` 与组件的 `clickable` 共用同一个实例。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
    haptic: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,        // 微弹：回弹带一点点越冲，但不晃
            stiffness = Spring.StiffnessMedium, // 快到跟手，又慢到能被看见
        ),
        label = "pressScale",
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(pressed) {
        if (pressed && haptic) {
            // Compose 抽象里最轻的一档；LongPress 档在连续点按上太吵
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
