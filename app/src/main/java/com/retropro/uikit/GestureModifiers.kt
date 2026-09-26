package com.retropro.uikit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定长按超时的「单击 + 长按」手势。
 *
 * ## 为什么不用 `detectTapGestures(onTap, onLongPress)`
 *
 * 它的长按阈值取自 `ViewConfiguration.longPressTimeoutMillis` —— **系统值**（通常 400ms）
 * 且会被无障碍设置改掉。计分板需要一个**确定的**阈值：
 * 太短会在快速加分时误触发标签面板，太长则球场上来不及。设计上定的是 350ms，
 * 所以必须自己控制。
 *
 * ## 行为约定
 *
 * | 手势 | 结果 |
 * |---|---|
 * | 在 [longPressMillis] 内抬起，且位移未超过 touchSlop | [onTap] |
 * | 按住超过 [longPressMillis] | [onLongPress]，随后吃掉剩余事件到抬起，不会再触发 tap |
 * | 按下后位移超过 touchSlop（滑动） | 两边都不触发 —— 计分板上滑动应被忽略 |
 *
 * ## [onLongPressAt] 与 [onLongPress] 的分工
 *
 * [onLongPress] 是"长按了"这个**事实**（计分板用它弹标签面板，不关心位置）。
 * [onLongPressAt] 额外回报**长按点在组件内的像素坐标** —— 长按菜单要把菜单
 * 贴在手指处，没有坐标就只能弹到固定位置。两者可同时使用。
 */
suspend fun PointerInputScope.detectTapAndLongPress(
    longPressMillis: Long = 350L,
    onPressChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit,
    onLongPress: () -> Unit = {},
    onLongPressAt: (Offset) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPressChanged(true)

        // 超时前的结果：Tap（按时抬起）/ Cancelled（滑走或指针消失）；返回 null 表示超时
        val settled = withTimeoutOrNull(longPressMillis) {
            var up = false
            var cancelled = false
            while (!up && !cancelled) {
                val event = awaitPointerEvent()
                val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    up = true
                } else if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    cancelled = true
                }
            }
            if (up && !cancelled) TapResult.Tap else TapResult.Cancelled
        }

        when (settled) {
            TapResult.Tap -> {
                onPressChanged(false)
                onTap()
            }

            null -> {
                // 已超时 → 长按。先回调，再把后续事件吃到抬起，
                // 否则松手会被判成第二次点击，等于一次长按加两分。
                onLongPress()
                onLongPressAt(down.position)
                drainUntilRelease()
                onPressChanged(false)
            }

            TapResult.Cancelled -> onPressChanged(false)
        }
    }
}

private enum class TapResult { Tap, Cancelled }

/** 吃掉剩余事件直到所有手指抬起。用于长按触发后避免二次回调。 */
private suspend fun AwaitPointerEventScope.drainUntilRelease() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return
    }
}
