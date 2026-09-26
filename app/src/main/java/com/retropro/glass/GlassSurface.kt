package com.retropro.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
// ---------------------------------------------------------------- Backdrop
// 包名 com.kyant.backdrop（已解包 aar 核实，groupId 与包名不同）
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.backdrop.shadow.Shadow
// ---------------------------------------------------------------- Haze
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 当前子树生效的玻璃档位。由 [GlassScene] 下发，业务组件默认读取它。
 */
val LocalGlassLevel = staticCompositionLocalOf { GlassLevel.SOLID_FALLBACK }

/**
 * Backdrop 场景的共享折射源图层。
 *
 * ## 为什么必须是"共享一个"而不是每个面板各自 new
 *
 * Kyant0 Backdrop 的模型是「**一个页面一个源图层**」：
 *
 * ```
 * val backdrop = rememberLayerBackdrop()          // 页面级，只建一次
 * Box {
 *     Box(Modifier.layerBackdrop(backdrop)) { 背景 }   // ← 标记折射源
 *     玻璃面板(...)                                     // ← 消费同一个 backdrop
 * }
 * ```
 *
 * 这与官方 `BackdropDemoScaffold` 完全一致。原因有两层：
 *
 *  1. **正确性**：`LayerBackdrop.drawBackdrop()` 的第一行是
 *     `val layerCoordinates = layerCoordinates ?: return` ——
 *     `layerCoordinates` 只由 `Modifier.layerBackdrop(backdrop)` 写入。
 *     面板若各自 new 一个 backdrop，就永远没有节点标记它，玻璃**一个像素都画不出来**。
 *  2. **性能**：每个面板一个 backdrop = 每帧录 N 次全屏背景。
 *     共享一个 = 每帧只录 1 次。
 */
val LocalLayerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * 当前子树共享的 HazeState（Haze 侧的等价物）。
 * 背景用 `hazeSource(state)` 标记，面板用 `hazeGlass(input = HazeInput.Sources(state))` 消费。
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * 液态玻璃面板 —— 本项目所有玻璃材质的**唯一出口**。
 *
 * 设计要点：
 *  1. 业务代码只调用 [GlassPanel]，永远不直接碰 Haze / Backdrop 的 API。
 *     第三方库升级、API 变更时只改这一个文件。
 *  2. 每次渲染前经过 [GlassGuard] 判断（组件是否死亡 / 是否全局熔断），
 *     任一不通过就退化为纯色卡片 —— 保证**永不白屏、永不崩溃**。
 *  3. 档位由 [GlassRuntime.activeLevel] 决定，可被设备能力、用户开关、熔断状态逐级压制。
 *
 * @param key 组件唯一标识。熔断按 key 独立计数，同一页面多个玻璃面板互不影响。
 * @param shape 必须是 RoundedCornerShape —— Backdrop 的 lens 与 Haze 的 shape() 都只接受可计算圆角的形状。
 * @param useVibrancy / [useBlur] / [useLens] / [useHighlight] / [useShadow]
 *        与官方 catalog 的 Glass Playground 同义：把每个效果分项开关，用于在真机上
 *        隔离"哪一层在起作用"。正式页面全部保持默认 true。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = AppShapes.Card,
    key: String = "glass-panel",
    level: GlassLevel = LocalGlassLevel.current,
    refraction: RefractionSpec = RefractionSpec.Standard,
    useVibrancy: Boolean = true,
    useBlur: Boolean = true,
    useLens: Boolean = true,
    useHighlight: Boolean = true,
    useShadow: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val active = resolveLevel(key, level)
    val backdrop = LocalLayerBackdrop.current
    val hazeState = LocalHazeState.current

    // ---- 调试面板接线（DebugUiState）：仅在面板开启时覆盖参数
    // 1) 全局圆角：覆盖任何传入 shape
    val effectiveShape: RoundedCornerShape =
        com.retropro.uikit.DebugUiState.debugShapeOrNull(shape) ?: shape
    // 2) 卡片透明度：整卡 alpha（含内容——调试语义下的 Card Opacity）
    val effectiveModifier: Modifier =
        if (com.retropro.uikit.DebugUiState.enabled) {
            modifier.alpha(com.retropro.uikit.DebugUiState.cardOpacity)
        } else modifier
    // 3) 折射强度：blur / refractionAmount / height 按倍率缩放（透光高光随之衰减）
    val effectiveRefraction: RefractionSpec =
        if (com.retropro.uikit.DebugUiState.enabled) {
            val k = com.retropro.uikit.DebugUiState.refractionIntensity
            refraction.copy(
                blurDp = refraction.blurDp * k,
                refractionHeightDp = refraction.refractionHeightDp * k,
                refractionAmountDp = refraction.refractionAmountDp * k,
            )
        } else refraction

    when {
        active == GlassLevel.BACKDROP_LENS && backdrop != null ->
            BackdropLensPanel(
                backdrop = backdrop,
                key = key,
                refraction = effectiveRefraction,
                useVibrancy = useVibrancy,
                useBlur = useBlur,
                useLens = useLens,
                useHighlight = useHighlight,
                useShadow = useShadow,
                modifier = effectiveModifier,
                panelShape = effectiveShape,
                contentPadding = contentPadding,
                content = content,
            )

        active == GlassLevel.HAZE_GLASS && hazeState != null ->
            HazeGlassPanel(hazeState, effectiveModifier, effectiveShape, contentPadding, content)

        // Android 12（API 31/32）：无 RuntimeShader，走纯模糊档（见 HazeBlurPanel）
        active == GlassLevel.HAZE_BLUR && hazeState != null ->
            HazeBlurPanel(hazeState, effectiveModifier, effectiveShape, contentPadding, content)

        // MIUIX_BLUR 在 M0 阶段先用纯色近似，M1 接入 miuiX-blur
        else -> SolidPanel(effectiveModifier, effectiveShape, contentPadding, content)
    }
}

/**
 * 折射强度参数。
 *
 * 数值全部取自 Kyant0 官方 catalog 的成熟取值：
 *  - 按钮（小面积）：`blur(2dp) + lens(12dp, 24dp)`
 *  - 面板（大面积）：`blur(4dp) + lens(16dp, 32dp)`
 *
 * ⚠️ 三者都**必须先 `vibrancy()`**。饱和度提升是"液态玻璃"与"磨砂玻璃"的分界线：
 * 没有它，玻璃就是一块灰扑扑的半透明板；有它，背后颜色才透出来。
 */
data class RefractionSpec(
    val label: String,
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val chromaticAberration: Boolean,
) {
    companion object {
        /** 轻：小面积元素，几乎不改变背景几何 */
        val Subtle = RefractionSpec("轻", blurDp = 2f, refractionHeightDp = 10f, refractionAmountDp = 18f, chromaticAberration = false)

        /** 标准：官方面板取值，日常默认 */
        val Standard = RefractionSpec("标准", blurDp = 4f, refractionHeightDp = 16f, refractionAmountDp = 32f, chromaticAberration = false)

        /** 强：官方 playground 上限，带色散，折射最明显 */
        val Strong = RefractionSpec("强", blurDp = 4f, refractionHeightDp = 24f, refractionAmountDp = 48f, chromaticAberration = true)

        /** 保持与原 enum 的 `entries` 用法兼容（M0 档位切换遍历它） */
        val entries: List<RefractionSpec> = listOf(Subtle, Standard, Strong)
    }
}

/**
 * 解析当前组件实际可用的档位。这是降级链的决策点。
 *
 * 注意：这里**不做限流降级**。曾经把「1 秒内录制次数超限」当作降级条件，
 * 但那会让面板在正常滚动时随机闪成纯色 —— 组合期调用无法表达"每帧"语义。
 * 幻影限流现在只作为渲染风暴的诊断计数，不再影响视觉（见 [GlassGuard.noteRecord]）。
 */
private fun resolveLevel(key: String, requested: GlassLevel): GlassLevel = when {
    !GlassRuntime.userEnabled -> GlassLevel.SOLID_FALLBACK
    GlassGuard.isGlobalTripped() -> GlassLevel.SOLID_FALLBACK
    GlassGuard.isComponentDead(key) -> GlassLevel.SOLID_FALLBACK
    else -> requested
}

/**
 * 玻璃场景容器：统一的 OLED 纯黑基底 + **折射源标记** + 顶部浮层槽位。
 *
 * [background] 是"玻璃要折射的内容"（光晕、色块、移动的球等）。
 * 关键：**折射源必须放在背景层，而不是玻璃面板内部** —— 玻璃只能折射它背后的东西。
 *
 * ## [overlay] 与 [content] 的区别（重要）
 *
 *  - [content] 画在**折射源之外**，正常参与布局，页面卡片都在这里。
 *  - [overlay] 画在 content 之上、同样在折射源之外，用于**浮动导航栏**这类"覆盖层"。
 *
 * 两者都**绝不能放进 `layerBackdrop` 的子树内**：玻璃面板若位于自己被录制的图层里，
 * 就会形成 record → read → re-record 的自引用（这正是历史上 RenderThread SIGSEGV 的成因）。
 *
 * 代价是浮层只能折射**背景**，折射不到它下面的页面内容。
 * 官方 catalog 的 `LiquidBottomTabs` 也是这个结构（只折射壁纸），所以照此实现；
 * 要让导航栏折射页面内容需要引入第二个捕获图层，成本翻倍，留待 M1 评估。
 */
@Composable
fun GlassScene(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassRuntime.activeLevel,
    background: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    when (level) {
        GlassLevel.BACKDROP_LENS -> {
            val backdrop = rememberLayerBackdrop()
            CompositionLocalProvider(
                LocalGlassLevel provides level,
                LocalLayerBackdrop provides backdrop,
            ) {
                Box(modifier.background(AppColors.Background)) {
                    // ★ 这一行是折射能否生效的开关。
                    //   去掉它，LayerBackdrop.layerCoordinates 永远为 null，
                    //   下面所有 drawBackdrop 都会在入口处 return，玻璃变成"什么都不画"。
                    //
                    // ⚠️⚠️ 这个 Box 里**只能放背景**，绝对不能放 content/overlay。
                    //   一旦玻璃面板落进自己被录制的图层，LayerBackdrop 就会在录制过程中
                    //   读到自身 DisplayList，形成自引用 → HWUI 在 prepareTreeImpl 里无限
                    //   递归 → RenderThread 栈溢出崩溃（实测崩溃栈深度 500+）。
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop)) {
                        // ★ 必须在**录制范围内部**铺一层不透明白底（此处是 OLED 纯黑）。
                        //   录制出的 GraphicsLayer 若含大面积 alpha=0 区域，合成端对
                        //   "透明像素的 RGB" 处理不一致，实测会整块泛白、把细条纹和描边的
                        //   对比全部吃掉。给图层一个不透明基底，这个歧义就消失了。
                        Box(Modifier.matchParentSize().background(AppColors.Background))
                        background()
                    }
                    content()
                    overlay()
                }
            }
        }

        GlassLevel.HAZE_GLASS, GlassLevel.HAZE_BLUR -> {
            val hazeState = rememberHazeState()
            CompositionLocalProvider(
                LocalGlassLevel provides level,
                LocalHazeState provides hazeState,
            ) {
                Box(modifier.background(AppColors.Background)) {
                    // 同 Backdrop：源节点内只放背景，content/overlay 必须在源之外
                    Box(Modifier.matchParentSize().hazeSource(hazeState)) {
                        Box(Modifier.matchParentSize().background(AppColors.Background))
                        background()
                    }
                    content()
                    overlay()
                }
            }
        }

        GlassLevel.MIUIX_BLUR, GlassLevel.SOLID_FALLBACK -> {
            CompositionLocalProvider(LocalGlassLevel provides level) {
                Box(modifier.background(AppColors.Background)) {
                    background()
                    content()
                    overlay()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 具体实现

/**
 * Backdrop 折射实现（最高档）。
 *
 * 完全照搬官方 catalog 的面板写法：
 * ```
 * vibrancy()                      // 饱和度 ×1.5，液态感的"通透"
 * blur(4.dp.toPx())               // 磨砂，官方面板值（不是 16dp，太大就把折射糊掉了）
 * lens(16.dp.toPx(), 32.dp.toPx())// 折射：高度 16dp / 位移 32dp
 * highlight = { Highlight.Plain } // 屏幕空间镜面高光（官方 catalog 统一用 Plain）
 * ```
 *
 * 这里**刻意不加 `.clip(shape)`**：`drawBackdrop` 内部的 `layoutLayerBlock` 已经
 * 自带了 `clip = true + shape + CompositingStrategy.Offscreen`，
 * 外面再 clip 一层只会多一次离屏合成。
 */
@Composable
private fun BackdropLensPanel(
    backdrop: LayerBackdrop,
    key: String,
    refraction: RefractionSpec,
    useVibrancy: Boolean,
    useBlur: Boolean,
    useLens: Boolean,
    useHighlight: Boolean,
    useShadow: Boolean,
    modifier: Modifier,
    panelShape: RoundedCornerShape,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { panelShape },
                effects = {
                    if (useVibrancy) vibrancy()
                    if (useBlur) blur(refraction.blurDp.dp.toPx())
                    if (useLens) {
                        lens(
                            refractionHeight = refraction.refractionHeightDp.dp.toPx(),
                            refractionAmount = refraction.refractionAmountDp.dp.toPx(),
                            depthEffect = true,
                            chromaticAberration = refraction.chromaticAberration,
                        )
                    }
                },
                highlight = if (useHighlight) DEFAULT_HIGHLIGHT else null,
                shadow = if (useShadow) DEFAULT_SHADOW else null,
                onDrawSurface = { drawRect(AppColors.GlassScrim) },
                onDrawBackdrop = { drawBackdrop ->
                    // 只做诊断计数，不改变视觉（见 GlassGuard.noteRecord）
                    GlassGuard.noteRecord(key)
                    drawBackdrop()
                },
            )
            .border(0.5.dp, AppColors.GlassLine, panelShape)
            .padding(contentPadding),
        content = content,
    )
}

/** 提为常量，避免每帧新建 lambda 实例（会让 DrawBackdropElement 永远不相等）。 */
private val DEFAULT_HIGHLIGHT: () -> Highlight = { Highlight.Plain }
private val DEFAULT_SHADOW: () -> Shadow = { Shadow.Default }

/**
 * Haze 玻璃实现（次高档）。
 *
 * ## ⚠️ 必须用 GlassStyle.clear，不能用 GlassStyle.regular
 *
 * 官方文档原文：
 * > GlassStyle.regular … Its brighter tone is calibrated against native iOS 27
 * > **light appearance**; it does not automatically reproduce native dark appearance.
 * > Prefer Regular when background content could interfere with labels or controls.
 * > Use Clear for controls over photos and video where the underlying content should remain visible.
 *
 * 我们是**OLED 纯黑底**。用 regular 的结果是整块面板变成亮白磨砂板
 * （实测面板内亮度从背景的 ~30 跳到 ~166），完全不是液态玻璃。
 * clear 保留更多背景、模糊较浅且跨尺寸一致，才是深色底上的正确起点。
 *
 * 若当前子树没有 [LocalHazeState]（即背景未被 [GlassScene] 标记为源），
 * 自动回退到纯色，避免出现"折射到空白"的怪现象。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun HazeGlassPanel(
    hazeState: HazeState,
    modifier: Modifier,
    panelShape: RoundedCornerShape,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    // ★ Android 12 / 12L 的就地降级。
    //
    // `hazeGlass` 的折射链路全建立在 AGSL RuntimeShader 上，而 `haze-glass` 模块内部
    // **没有任何 SDK 版本判断**（反汇编核实：整个模块零处 Build.VERSION.SDK_INT），
    // 在 31/32 上调用会直接崩。
    //
    // 这里**不相信传入的档位参数**：M0 验证页的档位切换器可以手动把 level 设成 HAZE_GLASS，
    // 设备能力检测被绕过。所以在这个唯一出口处再判一次能力，兜住所有调用方。
    if (!isRuntimeShaderSupported()) {
        HazeBlurPanel(hazeState, modifier, panelShape, contentPadding, content)
        return
    }
    Box(
        modifier = modifier
            .hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = GlassStyle.clear.then {
                    // 官方说明：backgroundColor 会在折射与模糊**之前**垫在被捕获内容下面。
                    // 这里与 Backdrop 路径共用同一个 GlassBackdropFill，
                    // 保证两条渲染路径、两种主题下的面板对比度一致。
                    backgroundColor(AppColors.GlassBackdropFill)
                    tint(Color.White.copy(alpha = if (AppColors.isDark) 0.06f else 0.10f))
                    shape(panelShape)
                },
                // performanceMode 沿用官方默认（Adaptive）：按效果负载自动调处理分辨率。
                // 官方建议先跑默认档，实测后再在 Quality / Balanced / Performance 之间选。
            )
            .border(0.5.dp, AppColors.GlassLine, panelShape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Haze 纯模糊实现（**Android 12 档**）。
 *
 * ## 为什么需要它
 *
 * `hazeGlass` 的折射链路建立在 AGSL `RuntimeShader` 上（API 33+），在 Android 12 / 12L
 * 上根本不存在 —— 而 `haze-glass` 模块内部**没有任何 SDK 版本判断**，直接调用会崩。
 * 但 `RenderEffect` 模糊从 API 31 就有（Haze 自己的 `platformIsBlurEnabledByDefault()`
 * 也正好以 31 为界），所以 31/32 完全能给出真正的毛玻璃观感。
 *
 * ## 与 [HazeGlassPanel] 的差异
 *
 * | | Haze 玻璃（33+） | Haze 模糊（31/32） |
 * |---|---|---|
 * | 模糊 | ✅ | ✅ |
 * | 折射 / 镜面高光 / 边缘光 | ✅ | ❌（需要 RuntimeShader） |
 * | 白色 tint | 由 GlassStyle 的 `tint()` 负责 | 由本函数的 `.background()` 负责 |
 *
 * tint 的位置差异是刻意的：`HazeBlurStyleScope` 没有 `tint()`，只有 `colorEffects()`，
 * 而 `background()` 画在 `hazeBlur()` **之后**（Compose 修饰符链靠前的先画），
 * 语义与 GlassStyle 的 tint 完全一致 —— 都是"盖在模糊层之上的白雾"。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun HazeBlurPanel(
    hazeState: HazeState,
    modifier: Modifier,
    panelShape: RoundedCornerShape,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            // ★ 圆角裁剪必须在这里做：Haze 的 blurredEdgeTreatment 只收
            //   androidx.compose.ui.draw.BlurredEdgeTreatment，而它只有 Rectangle / Unbounded
            //   两个公开取值（构造函数是 internal，Compose 没暴露"按自定义 Shape"的工厂）。
            //   所以先用 clip(圆角) 收边，再在样式里收成 Rectangle，两者叠加 == 圆角面板。
            .clip(panelShape)
            .hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = HazeBlurStyle.then {
                    blurEnabled(true)
                    // 面板面积大：半径给足，让背后的条纹/色球彻底糊成色块，
                    // 而不是"看得见轮廓的糊"。数值可调，真机看效果。
                    blurRadius(HazeBlurPanelRadius)
                    // 与另两条路径共用同一个 GlassBackdropFill，保证对比度一致
                    backgroundColor(AppColors.GlassBackdropFill)
                    blurredEdgeTreatment(BlurredEdgeTreatment.Rectangle)
                },
                // performanceMode 沿用官方默认（Adaptive）
            )
            .background(Color.White.copy(alpha = if (AppColors.isDark) 0.06f else 0.10f), panelShape)
            .border(0.5.dp, AppColors.GlassLine, panelShape)
            .padding(contentPadding),
        content = content,
    )
}

/** Android 12 档的模糊半径。纯模糊（无折射）时半径可以给得比 Backdrop 的 4dp 大得多。 */
private val HazeBlurPanelRadius = 20.dp

/**
 * 纯色兜底实现。玻璃层被剥离后 App 必须依然成立，这个 composable 就是保证。
 */
@Composable
private fun SolidPanel(
    modifier: Modifier,
    panelShape: RoundedCornerShape,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .background(AppColors.SurfaceFallback, panelShape)
            .border(0.5.dp, AppColors.GlassLine, panelShape)
            .padding(contentPadding),
        content = content,
    )
}
