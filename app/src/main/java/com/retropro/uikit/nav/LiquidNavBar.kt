package com.retropro.uikit.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.retropro.uikit.AppText
import com.retropro.uikit.nav.animation.DampedDragAnimation
import com.retropro.uikit.nav.animation.InteractiveHighlight
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppTypography
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 导航项。图标用 MIUIX 图标库的 ImageVector（`MiuixIcons.Regular.xxx`）。 */
data class NavItem(val icon: ImageVector, val label: String)

/**
 * 导航条容器的叠加色。
 *
 * 官方 catalog 的暗色取值是「近黑 40%」。白底上必须换成**白 72%** ——
 * 玻璃条要托在页面之上才看得出"条"的形状；如果继续用黑，
 * 白底上会出现一条灰带，和 MIUIX 的干净白底冲突。
 *
 * ⚠️ 写成 getter（而不是 `val`）：这两个值随主题变化，
 * 用 `val` 会在文件首次加载时把颜色固化，切主题后不跟着变。
 */
private val BarContainerColor: Color
    get() = if (AppColors.isDark) {
        Color(0xFF121212).copy(alpha = 0.40f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }

/** 药丸静息态的表面色。深色底加白提亮，浅色底加黑压暗 —— 都是为了让选中项"浮起来" */
private val PillRestSurface: Color
    get() = if (AppColors.isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.07f)
    }

private val AccentColor: Color get() = AppColors.Me

/**
 * 液态玻璃底部导航栏。
 *
 * **完整移植 Kyant0 `AndroidLiquidGlass` 的 `LiquidBottomTabs`**
 * （`components/LiquidBottomTabs.kt` + `components/LiquidBottomTab.kt`
 *  + `utils/DampedDragAnimation.kt` + `utils/InteractiveHighlight.kt`
 *  + `utils/DragGestureInspector.kt`，均 Apache-2.0）。
 * 这也正是 MemoCard 那份实现的来源。
 *
 * ## 四层结构（与官方逐行对应）
 *
 * ```
 * ① 玻璃条      drawBackdrop(页面 backdrop) + vibrancy + blur(8dp) + lens(24,24)
 *               layerBlock 随按压进度整体放大（+16dp / 条宽）
 * ② 第二层      同款整条玻璃，但 alpha(0f) 不可见，仅用于 layerBackdrop(tabsBackdrop)
 *               并叠加 accent 着色 —— 它是药丸的折射对象
 * ③ 选择药丸    drawBackdrop(combined(页面 backdrop, tabsBackdrop))
 *               + lens(10dp,14dp,色散) × 按压进度
 *               + Highlight / Shadow / InnerShadow（全部随按压进度淡入）
 *               + layerBlock 按 scaleX/scaleY 与速度做非等比拉伸
 * ④ 前景        LiquidBottomTab × N：可点击、按压缩放 1f→1.2f
 * ```
 *
 * ## 动效构成（全部来自官方，未简化）
 *
 * | 交互 | 实现 |
 * |---|---|
 * | 按下即跟手（无 touch slop） | `inspectDragGestures`（`DragGestureInspector.kt`） |
 * | 拖动换页 + 惯性吸附 | `DampedDragAnimation.valueAnimation` |
 * | 松手时的拉伸/挤压 | `DampedDragAnimation.velocityAnimation` |
 * | 按压放大（横向更明显） | `scaleX/scaleYAnimation`，`pressedScale = 78/56` |
 * | 按压处跟手高光 | `InteractiveHighlight`（AGSL 径向加色光） |
 * | 整条随拖动轻微平移 | `panelOffset`（`EaseOut` 缓动 × 方向符号） |
 *
 * @param backdrop 页面级共享折射源（`LocalLayerBackdrop.current`）。
 *        传 null 时退化为纯色条，保证导航栏永远可用、永不消失。
 */
@Composable
fun LiquidNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    if (backdrop == null || items.isEmpty()) {
        SolidNavBar(items, selectedIndex, onSelect, modifier)
        return
    }

    val tabsCount = items.size
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        // 官方算法：可用宽度扣掉两侧 4dp 内边距后均分
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount }

        // 整条随拖动溢出量做轻微平移（4dp 幅度，EaseOut 缓动）
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).coerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedIndex) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                    onSelect(targetIndex)
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部改选中项（例如点击另一个 Tab）时同步药丸；已相等则不动，避免回环
        LaunchedEffect(selectedIndex) {
            if (currentIndex != selectedIndex) {
                currentIndex = selectedIndex
                dampedDragAnimation.animateToValue(selectedIndex.toFloat())
            }
        }

        // 按压高光位置：跟随药丸中心
        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // Tab 内容（① 与 ② 共用同一份，保证折射副本与本体对得上）
        val tabContent: @Composable RowScope.() -> Unit = {
            items.forEachIndexed { index, item ->
                LiquidBottomTab(onClick = { onSelect(index) }) {
                    NavTabVisual(item = item, selected = index == currentIndex)
                }
            }
        }

        // ---- ① 玻璃条本体
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CapsuleShape },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(BarContainerColor) },
                )
                .then(interactiveHighlight.modifier)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = tabContent,
        )

        // ---- ② 不可见的第二层：既是药丸的折射对象，也是它自己的玻璃底
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CapsuleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(BarContainerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(AccentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = tabContent,
            )
        }

        // ---- ③ 选择药丸
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) {
                            dampedDragAnimation.value * tabWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                        }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { CapsuleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            refractionHeight = 10f.dp.toPx() * progress,
                            refractionAmount = 14f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        InnerShadow(
                            radius = 8f.dp * dampedDragAnimation.pressProgress,
                            alpha = dampedDragAnimation.pressProgress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(PillRestSurface, alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

/** 单个 Tab 的图标 + 文字。玻璃层与前景层共用同一份视觉，保证折射的副本与本体对得上。 */
@Composable
private fun NavTabVisual(
    item: NavItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) AccentColor else AppColors.TextSecondary
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Image(
            imageVector = item.icon,
            contentDescription = item.label,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(color),
        )
        AppText(
            text = item.label,
            style = AppTypography.Label,
            color = color,
        )
    }
}

/**
 * 降级版导航栏：没有折射源时使用。
 * 这是"玻璃层必须可剥离"原则的落点 —— 关掉玻璃，导航依然完整可用。
 */
@Composable
private fun SolidNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth()
            .background(AppColors.SurfaceFallback, CapsuleShape),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    NavTabVisual(item = item, selected = index == selectedIndex)
                }
            }
        }
    }
}
