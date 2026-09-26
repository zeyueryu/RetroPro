package com.retropro.glass

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 液态玻璃的熔断器。
 *
 * 直接来源于真实事故：上一代项目在 `layerBackdrop` 中发生
 * record → read → re-record 的循环渲染，触发 RenderThread SIGSEGV。
 * 本类就是为阻断这条路径而存在。
 *
 * 三重保护：
 *  1. [safely] —— 异常捕获。玻璃绘制抛错不会崩掉整个 App。
 *  2. [markFailure] —— 累计失败达到 [MAX_FAILURES] 后，该组件被永久降级为纯色，
 *     不再尝试任何实时效果。
 *  3. [noteRecord] —— 渲染风暴探测器。**只统计，不改变视觉**。
 *     用它替代早期的"限流即降级"：组合期调用无法表达"每帧"语义，
 *     限流降级会让玻璃面板在正常滚动时随机闪成纯色。
 */
object GlassGuard {

    private const val TAG = "GlassGuard"

    /** 探测窗口长度 */
    private const val WINDOW_MS = 1000L

    /**
     * 单个窗口内允许的最大背景录制次数，超过即判定为"渲染风暴"。
     *
     * 定这个数的依据：Backdrop 的正常工作方式就是**每帧录一次**，
     * 所以 120Hz 设备正常值就是 120/s。取 240（≈ 2× 120Hz）留足余量，
     * 只有真正失控的循环渲染（record → read → re-record）才会越过。
     */
    private const val MAX_RECORDS_PER_WINDOW = 240

    /** 同一组件允许的最大失败次数，超过则永久降级 */
    private const val MAX_FAILURES = 3

    private val windowStartAt = ConcurrentHashMap<String, Long>()
    private val windowRecords = ConcurrentHashMap<String, AtomicInteger>()
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    /** 全局熔断：一旦置位，本次运行不再尝试最高档，沿降级链退让 */
    private val globalTripped = AtomicInteger(0)

    /**
     * 熔断回调。由 [GlassRuntime] 在 `init` 时注册，用于执行**逐级降档**。
     * 用回调而不是直接互相引用，是为了让熔断器保持纯粹、不反向依赖运行时状态。
     */
    var onTrip: ((String) -> Unit)? = null

    private val totalFailures = AtomicInteger(0)
    private val stormCount = AtomicInteger(0)

    // ---------------------------------------------------------------- 渲染风暴探测

    /**
     * 记录一次背景录制。**返回值不参与任何视觉决策**，只用于诊断。
     *
     * 调用点：`BackdropLensPanel` 的 `onDrawBackdrop`，即真正的绘制路径上。
     *
     * @return true 表示本窗口仍未越过风暴阈值；false 表示已越过（仅计数并打日志）。
     */
    fun noteRecord(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val start = windowStartAt[key]

        if (start == null || now - start >= WINDOW_MS) {
            windowStartAt[key] = now
            windowRecords.getOrPut(key) { AtomicInteger(0) }.set(1)
            return true
        }

        val records = windowRecords.getOrPut(key) { AtomicInteger(0) }
        if (records.get() >= MAX_RECORDS_PER_WINDOW) {
            stormCount.incrementAndGet()
            Log.e(
                TAG,
                "检测到渲染风暴：组件 [$key] 在 1 秒内录制超过 $MAX_RECORDS_PER_WINDOW 次，疑似循环渲染",
            )
            return false
        }
        records.incrementAndGet()
        return true
    }

    // ---------------------------------------------------------------- 异常保护

    /**
     * 在熔断保护下执行玻璃渲染相关的代码。
     * 任何异常都会被吞掉并计入失败，返回 null。
     */
    inline fun <T> safely(key: String, block: () -> T): T? {
        return try {
            if (isComponentDead(key)) null else block()
        } catch (t: Throwable) {
            markFailure(key, t)
            null
        }
    }

    // ---------------------------------------------------------------- 失败计数

    /** 记录一次失败；达到阈值后该组件永久降级 */
    fun markFailure(key: String, throwable: Throwable) {
        totalFailures.incrementAndGet()
        val counter = failureCounts.getOrPut(key) { AtomicInteger(0) }
        val count = counter.incrementAndGet()

        Log.e(TAG, "玻璃组件 [$key] 第 $count 次失败，已降级保护", throwable)

        if (count >= MAX_FAILURES) {
            Log.e(TAG, "玻璃组件 [$key] 失败达 $MAX_FAILURES 次，永久降级为纯色")
        }
    }

    /** 该组件是否已被永久降级 */
    fun isComponentDead(key: String): Boolean {
        val counter = failureCounts[key] ?: return false
        return counter.get() >= MAX_FAILURES
    }

    // ---------------------------------------------------------------- 熔断降级

    /**
     * 触发熔断降级（例如连续多次 RenderThread 相关异常）。
     *
     * 注意：这里**不再直接把画面打成纯色**，而是通过 [onTrip] 通知
     * [GlassRuntime] 沿降级链退一级（Backdrop → Haze → 纯色）。
     * 一次性跳到纯色会白白浪费中间档，与"四级降级链"的设计不符。
     */
    fun tripGlobal(reason: String) {
        globalTripped.set(1)
        Log.e(TAG, "熔断触发：$reason —— 沿降级链退让")
        onTrip?.invoke(reason)
    }

    /** 本次运行是否触发过熔断降级 */
    fun isGlobalTripped(): Boolean = globalTripped.get() == 1

    /** 手动恢复（设置页"重置玻璃失败计数"用） */
    fun resetAll() {
        windowStartAt.clear()
        windowRecords.clear()
        failureCounts.clear()
        globalTripped.set(0)
        totalFailures.set(0)
        stormCount.set(0)
        Log.i(TAG, "玻璃熔断状态已重置")
    }

    // ---------------------------------------------------------------- 诊断信息

    /** M0 验证页展示用的运行时统计 */
    fun stats(): Stats = Stats(
        globalTripped = isGlobalTripped(),
        totalFailures = totalFailures.get(),
        stormDetections = stormCount.get(),
        deadComponents = failureCounts.count { it.value.get() >= MAX_FAILURES },
        trackedComponents = failureCounts.size,
    )

    data class Stats(
        val globalTripped: Boolean,
        val totalFailures: Int,
        /** 渲染风暴探测次数（1 秒内录制越界）。正常应恒为 0 */
        val stormDetections: Int,
        val deadComponents: Int,
        val trackedComponents: Int,
    )
}
