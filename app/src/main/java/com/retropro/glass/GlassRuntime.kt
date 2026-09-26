package com.retropro.glass

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃运行时状态：把「设备能力」「用户开关」「熔断降级」三者合成的最终档位。
 *
 * 最终档位 = 用户开关 ? (降级档位 ?: 设备能力上限) : 纯色
 * 任何一项为否，就向降级链的下游退让。
 */
object GlassRuntime {

    private const val TAG = "GlassRuntime"

    /** 设备检测结果，Application 启动时写入 */
    var detection: GlassCapability.Detection? by mutableStateOf(null)
        private set

    // 下划线私有状态 + 只读公开属性：
    // 避免 `var userEnabled` 自动生成的 setter 与 setUserEnabled() 产生 JVM 签名冲突
    private var _userEnabled: Boolean by mutableStateOf(true)

    /** 用户总开关（设置页可关闭液态玻璃） */
    val userEnabled: Boolean get() = _userEnabled

    /** 当前生效档位，供 UI 读取 */
    var activeLevel: GlassLevel by mutableStateOf(GlassLevel.SOLID_FALLBACK)
        private set

    /**
     * 手动档位覆盖。非 null 时无视设备能力上限，直接使用该档位。
     *
     * 用途：M0 验证页的档位切换器 —— 在真机上一次点击就能对比
     * Backdrop / Haze / 纯色 三条渲染路径，而不用改代码重编。
     */
    var overrideLevel: GlassLevel? by mutableStateOf(null)
        private set

    /**
     * 熔断降级后当前停留的档位。null 表示尚未降级。
     *
     * 降级是**逐级**的：Backdrop → Haze → 纯色。
     * 早期实现是「一熔断就直接纯色」，跳过了中间档，与设计里的四级降级链不符。
     */
    private var degradedLevel: GlassLevel? by mutableStateOf(null)

    /** 是否发生过熔断降级 */
    val isDegraded: Boolean get() = degradedLevel != null

    fun init(context: Context) {
        val result = GlassCapability.detect(context)
        detection = result
        // 异常熔断 → 逐级退让（用回调注册，避免两个 object 互相直接引用）
        GlassGuard.onTrip = { reason -> degrade(reason) }
        recompute()
    }

    fun setUserEnabled(enabled: Boolean) {
        _userEnabled = enabled
        recompute()
    }

    fun toggleUserEnabled() = setUserEnabled(!userEnabled)

    /** 设置档位覆盖；传 null 恢复"按设备能力自动"。覆盖会同时清掉已降级状态 */
    fun setOverride(level: GlassLevel?) {
        overrideLevel = level
        degradedLevel = null
        recompute()
    }

    /**
     * 玻璃渲染持续异常时调用：沿降级链退一档。
     *
     * `MIUIX_BLUR` 目前跳过 —— `miuix-blur` 尚未接入（M1 计划），
     * 与其显示一个名不符实的"MIUIX 模糊"，不如直接退到纯色。
     */
    fun degrade(reason: String) {
        val current = degradedLevel ?: baseLevel()
        val next = when (current.next()) {
            GlassLevel.MIUIX_BLUR -> GlassLevel.SOLID_FALLBACK
            else -> current.next()
        }
        if (next == current) return
        degradedLevel = next
        Log.e(TAG, "玻璃降级至 ${next.label}：$reason")
        recompute()
    }

    /** 重置熔断并重新计算档位 */
    fun resetGuard() {
        GlassGuard.resetAll()
        degradedLevel = null
        recompute()
    }

    private fun baseLevel(): GlassLevel =
        overrideLevel ?: detection?.maxLevel ?: GlassLevel.SOLID_FALLBACK

    private fun recompute() {
        activeLevel = when {
            !userEnabled -> GlassLevel.SOLID_FALLBACK
            else -> degradedLevel ?: baseLevel()
        }
    }

    /** 供诊断面板展示的一行摘要 */
    fun summary(): String {
        val d = detection ?: return "未初始化"
        return "${activeLevel.label} · ${d.reason}"
    }
}
