package com.retropro.uikit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.retropro.uikit.theme.AppColors
import kotlin.math.roundToInt

/**
 * 全 App 共享的页面背景 —— **玻璃的折射对象**。
 *
 * 这是玻璃能不能"看起来像玻璃"的前提：OLED 纯黑上如果没有可折射的结构，
 * 液态玻璃就退化成一块半透明方板。因此本文件提供三层内容，按"对折射的证明力"排序：
 *
 *  1. **细条纹（pinstripes）**：竖直直线。直线在玻璃边缘弯折 / 位移，
 *     是折射最无歧义的证据 —— 模糊只会让线变虚，绝不会让线弯。
 *  2. **描边圆环**：大半径圆弧，弯曲方向肉眼可辨。
 *  3. **移动色球**：高饱和圆斑缓慢横穿，观察折射在**动态内容**上的表现。
 *
 * 另有若干静态光晕，让纯黑底上有颜色可以透出来。
 *
 * ## 性能约定（务必遵守）
 *  - 所有动画元素一律使用 `Modifier.offset { }`（lambda 版）：动画值只在**布局阶段**
 *    读取，组合与子组合完全跳过。用 Dp 版 `offset(x, y)` 会每帧重排。
 *  - 动画的 `Brush` 必须 `remember`，否则每帧重建。
 *  - **不要用 `BoxWithConstraints` 包动画元素** —— 它是 `SubcomposeLayout`，会每帧重新子组合。
 *    尺寸测量只在本文件顶层做一次，把 px 结果传进子组件。
 */
@Composable
fun AppBackground() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val style = com.retropro.util.AppPrefs.Live.backgroundStyle

    // 自定义图片：按屏幕尺寸降采样解码，防止 4000px 照片直接把内存打爆
    val density0 = LocalDensity.current
    val bgImage = if (style == com.retropro.util.AppPrefs.BackgroundStyle.IMAGE) {
        remember(style) {
            runCatching {
                val f = com.retropro.util.AppPrefs.backgroundImageFile(context)
                if (!f.exists()) return@runCatching null
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                val targetW = with(density0) { 1080.dp.toPx() }.toInt()
                var sample = 1
                while (opts.outWidth / (sample * 2) >= targetW) sample *= 2
                val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, decodeOpts)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    } else {
        null
    }

    // 图片 / 纯色模式：装饰退场，只剩基底。折射会变弱——这是用户选择的代价，
    // 但图片本身自带丰富纹理，玻璃效果反而往往更好
    if (style == com.retropro.util.AppPrefs.BackgroundStyle.SOLID) {
        Box(Modifier.fillMaxSize().background(AppColors.Background))
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // ---- 自定义图片层 + 可读性暗化层
        if (style == com.retropro.util.AppPrefs.BackgroundStyle.IMAGE) {
            if (bgImage != null) {
                androidx.compose.foundation.Image(
                    bitmap = bgImage,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 深色压黑、浅色提白，保证文字可读。浓度可调（外观页滑块）：
            // 固定 0.6 白纱会把整屏蒙雾——默认 0.4 + 用户自调
            val mask = com.retropro.util.AppPrefs.Live.bgMaskOpacity
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (AppColors.isDark) Color.Black.copy(alpha = mask)
                        else Color.White.copy(alpha = mask),
                    ),
            )
            return@BoxWithConstraints
        }

        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // ---- 静态光晕：让基底有可透出的颜色
        // ⚠️ 必须用 Alignment 锚定，不要用绝对 dp。
        //    绝对 dp 在不同屏高上会跑出屏幕（模拟器高 574dp，真机约 800dp）；
        //    而底部光晕是导航栏玻璃折射的唯一来源 —— 跑没了导航栏就只剩一条纯色。
        Glow(
            color = AppColors.GlowBlue, sizeDp = 620,
            align = Alignment.TopStart, xDp = -140, yDp = -80,
        )
        Glow(
            color = AppColors.GlowTeal, sizeDp = 560,
            align = Alignment.CenterEnd, xDp = 90, yDp = -60,
        )
        Glow(
            color = AppColors.GlowOrange, sizeDp = 700,
            align = Alignment.BottomCenter, xDp = -20, yDp = 120,
        )

        // ---- 简约模式：只有光晕，无条纹/圆环/色球
        if (style == com.retropro.util.AppPrefs.BackgroundStyle.MINIMAL) {
            return@BoxWithConstraints
        }

        // ---- 竖直细条纹：折射的"标尺"。颜色随主题反转（白底用深色条纹）
        Pinstripes(pitchDp = 20f, strokeDp = 3f)

        // ---- 描边圆环：圆弧弯折肉眼可辨
        Ring(AppColors.RingCool, sizeDp = 240, xDp = 30, yDp = 120, strokeDp = 2f)
        Ring(AppColors.RingWarm, sizeDp = 170, xDp = 200, yDp = 210, strokeDp = 2f)

        // ---- 移动色球：动态内容上的折射
        //     最后一颗放到 72% 屏高处，会周期性地从底部导航栏后面穿过，
        //     用来观察"运动物体经过玻璃条"时的折射/位移。
        //     白底上色球不透明度要压下来，否则会盖住文字。
        MovingBall(
            color = AppColors.Me, sizeDp = 112, durationMs = 5200, reverse = false,
            travelPx = widthPx - with(density) { 112.dp.toPx() },
            verticalPx = heightPx * 0.20f,
            headAlpha = BALL_HEAD_ALPHA,
        )
        MovingBall(
            color = AppColors.Opponent, sizeDp = 86, durationMs = 3900, reverse = true,
            travelPx = widthPx - with(density) { 86.dp.toPx() },
            verticalPx = heightPx * 0.30f,
            headAlpha = BALL_HEAD_ALPHA,
        )
        MovingBall(
            color = AppColors.Success, sizeDp = 62, durationMs = 6600, reverse = false,
            travelPx = widthPx - with(density) { 62.dp.toPx() },
            verticalPx = heightPx * 0.72f,
            headAlpha = BALL_HEAD_ALPHA,
        )
    }
}

/**
 * 色球中心的不透明度。
 *
 * 深色底上色球要够亮才有"透出来"的感觉；白底上同样的亮度会变成一块
 * 硬边色斑盖住内容，所以压到 0.55。用 `alpha` 而不是换颜色，
 * 是为了保住色相（折射看到的是同一族颜色，只是浓淡不同）。
 */
private val BALL_HEAD_ALPHA: Float
    get() = if (AppColors.isDark) 1f else 0.55f

/**
 * 固定尺寸光晕。静态，无动画，不产生任何每帧重组。
 *
 * [align] 决定锚点（TopStart / CenterEnd / BottomCenter …），
 * 之后再用 [xDp] / [yDp] 做偏移，这样任何屏高都能保证光晕落在该在的象限。
 */
@Composable
private fun BoxScope.Glow(
    color: Color,
    sizeDp: Int,
    align: Alignment,
    xDp: Int,
    yDp: Int,
) {
    // ⚠️ 终点色必须写 color.copy(alpha = 0f)，不要用 Color.Transparent。
    //    Color.Transparent 的 RGB 是 (0,0,0)，插值到它等于往"黑色"过渡；
    //    在录制进 GraphicsLayer 时这种"透明黑"的处理不一致，实测会把整块泛白。
    val brush = remember(color) {
        Brush.radialGradient(listOf(color, color.copy(alpha = 0f)))
    }
    Box(
        Modifier
            .align(align)
            .offset(x = xDp.dp, y = yDp.dp)
            .size(sizeDp.dp)
            .background(brush = brush, shape = CircleShape),
    )
}

/**
 * 竖直细条纹。
 *
 * 用 `drawBehind` 循环画线：全屏约 60 次 `drawLine`，代价可忽略，
 * 换来的是"直线会不会在玻璃边缘弯折"这个明确判断依据。
 */
@Composable
private fun Pinstripes(pitchDp: Float, strokeDp: Float) {
    val density = LocalDensity.current
    val pitch = with(density) { pitchDp.dp.toPx() }
    val stroke = with(density) { strokeDp.dp.toPx() }
    // 颜色取自调色板（随主题反转）。remember 的 key 里带上颜色，
    // 否则切主题后条纹会保持旧色。
    val color = AppColors.Stripe

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                    )
                    x += pitch
                }
            },
    )
}

/** 描边圆环。不透明度随主题：白底上要更实一点才看得出弧线 */
@Composable
private fun Ring(color: Color, sizeDp: Int, xDp: Int, yDp: Int, strokeDp: Float) {
    val width = with(LocalDensity.current) { strokeDp.dp.toPx() }
    val alpha = if (AppColors.isDark) 0.55f else 0.35f
    Box(
        Modifier
            .offset(x = xDp.dp, y = yDp.dp)
            .size(sizeDp.dp)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = size.minDimension / 2f - width,
                    style = Stroke(width = width),
                )
            },
    )
}

/**
 * 匀速横穿的色球。
 *
 * 关键：`progress` 是 `State<Float>`，**刻意不用 `by` 委托**。
 * `by` 会在组合期订阅动画值 → 每帧重组；而在 `offset { }` 的 lambda 里读 `progress.value`，
 * 订阅的是**布局阶段**，动画只触发重新摆放，完全跳过组合与重排测量。
 */
@Composable
private fun MovingBall(
    color: Color,
    sizeDp: Int,
    durationMs: Int,
    reverse: Boolean,
    travelPx: Float,
    verticalPx: Float,
    headAlpha: Float,
) {
    val transition = rememberInfiniteTransition(label = "ball-$sizeDp")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "progress-$sizeDp",
    )

    val brush = remember(color, headAlpha) {
        Brush.radialGradient(
            listOf(
                color.copy(alpha = headAlpha),
                color.copy(alpha = headAlpha * 0.30f),
            ),
        )
    }

    Box(
        Modifier
            .offset {
                val p = progress.value
                val x = if (reverse) travelPx * (1f - p) else travelPx * p
                IntOffset(x.roundToInt(), verticalPx.roundToInt())
            }
            .size(sizeDp.dp)
            .background(brush = brush, shape = CircleShape),
    )
}
