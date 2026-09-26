package com.retropro.glass

/**
 * 液态玻璃的渲染档位。
 *
 * 顺序即降级链：任一层不可用或运行期失败，自动向下一层退让，**永不白屏**。
 *
 * 对应《开源库选型与视觉方案.md》§9.1「液态玻璃的熔断机制」。
 */
enum class GlassLevel(val label: String, val description: String) {

    /** 最优：Kyant0 Backdrop 的折射（lens），真正的"液态"感 */
    BACKDROP_LENS(
        label = "Backdrop 折射",
        description = "模糊 + lens 折射 + 镜面高光。视觉最强，但依赖 RuntimeShader。",
    ),

    /** 次优：Haze 2.0 Glass，模糊 + 折射 + 光照，有性能档位与自动降级 */
    HAZE_GLASS(
        label = "Haze 玻璃",
        description = "模糊 + 折射 + 光照。比 Backdrop 更稳，有 HazePerformanceMode 兜底。",
    ),

    /**
     * Android 12（API 31/32）档：Haze 纯模糊，**无折射**。
     *
     * 折射走 RuntimeShader（API 33+），31/32 上不存在；而 RenderEffect 模糊从 31 就有，
     * 所以这两个版本仍能得到真正的毛玻璃观感，而不是退化成纯色卡片。
     * 同时它也是 Haze 系内部的性能兜底档（比 [HAZE_GLASS] 便宜）。
     */
    HAZE_BLUR(
        label = "Haze 模糊",
        description = "仅模糊，无折射。Android 12 的默认档位。",
    ),

    /** 再次：MIUIX 自带模糊（miuix-blur），能力最保守 */
    MIUIX_BLUR(
        label = "MIUIX 模糊",
        description = "仅模糊，无折射。**尚未接入**（miuix-blur 声明 minSdk 33，已移除），当前退化为纯色。",
    ),

    /** 兜底：纯半透明色块。关闭玻璃总开关或全部失败时使用 */
    SOLID_FALLBACK(
        label = "纯色兜底",
        description = "无任何实时效果，纯色卡片。App 依然完整可用。",
    ),
    ;

    /** 该档位是否需要捕获背景内容（建立 hazeSource / backdrop 层） */
    val needsBackdropCapture: Boolean
        get() = this == BACKDROP_LENS || this == HAZE_GLASS || this == HAZE_BLUR

    /** 按降级链取下一档，最后一档返回自身 */
    fun next(): GlassLevel = entries.getOrElse(ordinal + 1) { SOLID_FALLBACK }
}
