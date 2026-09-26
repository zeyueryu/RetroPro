package com.retropro.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * 帧率采集器。
 *
 * M0 的验收标准是「真机连续操作 10 分钟不崩溃、不发热、不丢帧」，
 * 因此需要把 FPS 与卡顿帧数显性化，而不是靠感觉。
 *
 * ## ⚠️ 性能红线（v0.2 修正）
 *
 * 上一版把 `totalFrames` / `jankFrames` 也做成 `mutableStateOf` 并在**每帧**自增，
 * 而诊断卡片又在组合期读它们 —— 结果是**监控代码本身每帧强制重组一个玻璃面板**，
 * 面板重组触发 `DrawBackdropNode.invalidateDrawCache()` → 每帧重建 RenderEffect 链。
 *
 * 在骁龙 8E5 这种强芯片上表现为"帧率剧烈波动"：芯片越强，刷新率越高，
 * 每帧被拖进重组的次数越多，掉得越明显。
 *
 * 修正原则：**每帧只写普通局部变量，1 秒窗口结束时才发布一次快照 state。**
 * 这样每秒最多触发一次重组，与刷新率无关。
 */
class FrameStats internal constructor() {

    /** 最近一个 1 秒窗口的帧率 */
    var fps by mutableFloatStateOf(0f)
        internal set

    /** 最近一个 1 秒窗口内最差单帧耗时（毫秒） */
    var worstFrameMs by mutableFloatStateOf(0f)
        internal set

    /** 累计卡顿率，0f~1f */
    var jankRate by mutableFloatStateOf(0f)
        internal set

    /** 累计帧数 */
    var totalFrames by mutableIntStateOf(0)
        internal set

    /** 累计卡顿帧数（单帧 > 2 个刷新周期） */
    var jankFrames by mutableIntStateOf(0)
        internal set

    /** 采样总时长（毫秒） */
    var elapsedMs by mutableLongStateOf(0L)
        internal set
}

private const val WINDOW_NS = 1_000_000_000L

/** 60Hz 下两个刷新周期约 33ms，超过即视为卡顿 */
private const val JANK_THRESHOLD_NS = 33_000_000L

@Composable
fun rememberFrameStats(): FrameStats {
    val stats = remember { FrameStats() }

    LaunchedEffect(Unit) {
        // ↓ 以下全部是普通局部变量：每帧自增不产生任何快照写入，不触发重组
        var windowStart = 0L
        var windowFrames = 0
        var lastFrame = 0L
        var worstThisWindow = 0L
        var totalFrames = 0
        var jankFrames = 0
        val sessionStart = System.nanoTime()

        while (true) {
            withFrameNanos { now ->
                if (windowStart == 0L) {
                    windowStart = now
                    lastFrame = now
                    return@withFrameNanos
                }

                windowFrames++
                totalFrames++

                val delta = now - lastFrame
                if (delta > worstThisWindow) worstThisWindow = delta
                if (delta > JANK_THRESHOLD_NS) jankFrames++
                lastFrame = now

                val elapsed = now - windowStart
                if (elapsed >= WINDOW_NS) {
                    // ★ 唯一的 state 写入点：每秒一次
                    stats.fps = windowFrames * 1_000_000_000f / elapsed
                    stats.worstFrameMs = worstThisWindow / 1_000_000f
                    stats.totalFrames = totalFrames
                    stats.jankFrames = jankFrames
                    stats.jankRate =
                        if (totalFrames == 0) 0f else jankFrames.toFloat() / totalFrames
                    stats.elapsedMs = (now - sessionStart) / 1_000_000L

                    windowStart = now
                    windowFrames = 0
                    worstThisWindow = 0L
                }
            }
        }
    }

    return stats
}
