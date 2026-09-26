package com.retropro.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备液态玻璃能力检测。
 *
 * 在 Application 启动时跑一次，结果写入 [GlassRuntime]。检测维度：
 *  1. RuntimeShader 是否可用（折射 lens 的硬前提，API 33+）
 *  2. 是否低内存设备（isLowRamDevice）
 *  3. memoryClass —— **只作为信息展示，不参与档位决策**
 *
 * ## ⚠️ 为什么 memoryClass 不能用来决定档位
 *
 * memoryClass 就是 `dalvik.vm.heapgrowthlimit`（每进程 Java 堆上限），
 * 它与 GPU 能力**毫无关系**，而 **192m 是大量设备的出厂默认值**，
 * 旗舰机也一样。早期版本用「<192 → MIUIX，192~383 → Haze，≥384 → Backdrop」
 * 分档，结果是把一堆正常旗舰机误判进 Haze 档。
 *
 * 实测反例：模拟器 `dalvik.vm.heapgrowthlimit = 192m`，
 * 于是被分到 Haze；切到 Backdrop 后同一台机器 FPS 从 5 涨到 55。
 *
 * 现在的策略：**只要能跑 RuntimeShader 且不是低内存设备，就直接上 Backdrop**。
 * 真机实测不合适时，用 M0 页的「档位切换」手动覆盖（[GlassRuntime.setOverride]）。
 *
 * 设计原则仍然是：**能力不足只是降级，绝不阻止 App 运行**。
 */
object GlassCapability {

    /** 折射（lens）的硬前提：AGSL RuntimeShader，Android 13 起 */
    private const val MIN_API_FOR_SHADER = Build.VERSION_CODES.TIRAMISU

    /**
     * 实时模糊的硬前提：RenderEffect，Android 12 起。
     *
     * 这个常量与 `minSdk` **必须保持一致**（当前都是 31）—— 正因为两者相等，
     * 下面的代码不需要再运行时判断它。若将来把 minSdk 降到 31 以下，
     * 必须把这个判断加回去（lint 的 ObsoleteSdkInt 会在那时自动消失）。
     */
    private const val MIN_API_FOR_RENDER_EFFECT = Build.VERSION_CODES.S

    data class Detection(
        /** 本机可用的最高档位 */
        val maxLevel: GlassLevel,
        /** RuntimeShader 是否可用 */
        val runtimeShaderSupported: Boolean,
        /** 是否低内存设备 */
        val lowRamDevice: Boolean,
        /** 单进程可用堆上限（MB）。仅供展示，不参与档位决策 */
        val memoryClassMb: Int,
        /** 人类可读的判定说明 */
        val reason: String,
    )

    fun detect(context: Context): Detection {
        val runtimeShaderSupported = Build.VERSION.SDK_INT >= MIN_API_FOR_SHADER

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = am?.isLowRamDevice ?: false
        val memoryClassMb = am?.memoryClass ?: 0

        // Android 12 / 12L：没有 RuntimeShader（折射），但 RenderEffect 模糊从 API 31 就有
        // （与 minSdk 相同，所以无需再判断）→ 走 Haze 纯模糊档，卡片仍是毛玻璃而非纯色。
        if (!runtimeShaderSupported) {
            return Detection(
                maxLevel = GlassLevel.HAZE_BLUR,
                runtimeShaderSupported = false,
                lowRamDevice = lowRam,
                memoryClassMb = memoryClassMb,
                reason = "Android ${Build.VERSION.SDK_INT}：无 RuntimeShader → " +
                    "Haze 纯模糊（minSdk $MIN_API_FOR_RENDER_EFFECT 起实时模糊必然可用）",
            )
        }

        val level = if (lowRam) GlassLevel.MIUIX_BLUR else GlassLevel.BACKDROP_LENS

        val reason = buildString {
            append("API ").append(Build.VERSION.SDK_INT)
            append(" · 堆上限 ").append(memoryClassMb).append("MB（不参与分档）")
            if (lowRam) append(" · 低内存设备")
        }

        return Detection(
            maxLevel = level,
            runtimeShaderSupported = true,
            lowRamDevice = lowRam,
            memoryClassMb = memoryClassMb,
            reason = reason,
        )
    }
}
