package com.retropro.uikit

import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.retropro.glass.GlassPanel
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 共用表单控件。
 *
 * 所有输入控件都包一层，理由和 [AppText] 一样：
 *  - 业务代码不直接碰 MIUIX 的 API，库升级只改这里
 *  - 表单的视觉（标签字号、间距、必填标记）统一，不用每个页面各写一遍
 *
 * 注意：这里的控件**不带玻璃材质**。玻璃只用于卡片级别的容器（[SectionCard]），
 * 输入框内部再加模糊会让文字对比度掉到不可读 —— 这是 OLED 纯黑上必须守的线。
 */

/**
 * 表单分组卡：玻璃卡片 + 标题 + 可选说明。
 *
 * [useLens] 用于**高频重绘页面**（计分板、逐球列表）：这些页面按安全规则
 * 要禁用 `lens` 折射，只保留轻量模糊。默认 true，普通页面不用管。
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    useLens: Boolean = true,
    content: @Composable () -> Unit,
) {
    GlassPanel(
        key = "section.$title",
        modifier = modifier.fillMaxWidth(),
        useLens = useLens,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column {
            AppText(title, AppTypography.CardTitle, AppColors.TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                AppText(subtitle, AppTypography.Caption, AppColors.TextTertiary)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * **严格 MIUIX 卡片** —— 零液态玻璃。
 *
 * 与 [SectionCard] 的差别只在材质：
 *  - 不透明实色底（零 alpha，不与背景做任何混合）
 *  - 无模糊 / 无折射 / 无镜面高光 / 无投影
 *  - 1dp 发丝描边划分层级
 *
 * 用于明确要求"不用液态效果"的页面（「我的」及其二级页）。
 * 版式与 [SectionCard] 完全一致（标题 / 说明 / 18dp 内边距），
 * 因此替换后布局零位移、信息层级不退化。
 */
@Composable
fun MiuixCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.Card)
            .background(AppColors.SurfaceFallback)
            .border(1.dp, AppColors.GlassLine, AppShapes.Card)
            .padding(18.dp),
    ) {
        AppText(title, AppTypography.CardTitle, AppColors.TextPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            AppText(subtitle, AppTypography.Caption, AppColors.TextTertiary)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** 文本输入。转发 MIUIX `TextField` */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
    )
}

/**
 * 单选 chip 组。
 *
 * 用 chip 而不是下拉菜单：选项只有 2~5 个，全部可见比"点开才知道有什么"快得多，
 * 而且选中态一眼可辨 —— 这是"减少录入摩擦"的具体落点。
 */
@Composable
fun <T> ChipSelector(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            SelectableChip(
                text = labelOf(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** 多选 chip 组（对手风格标签、心得标签用） */
@Composable
fun MultiChipSelector(
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            SelectableChip(
                text = option,
                selected = option in selected,
                onClick = { onToggle(option) },
            )
        }
    }
}

/**
 * 单个可选 chip（真·胶囊：两端半圆，由 [AppShapes.Chip] = 100dp 圆角保证）。
 *
 * 选中用 HyperOS 蓝，未选中用极低不透明度中性填充。
 *
 * [outlined] 未选中态的 1dp 发丝描边。**默认关闭**：项目里绝大多数 chip 都贴在
 * 卡片实底上，实底已经把边界托住了，再加描边是多余的一圈噪点。
 * 只有**横向滚动胶囊行**（胶囊在滑动中会与各种背景边缘交错）才需要它 —— 见 [HorizontalChipRow]。
 */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = LocalChipOutlined.current,
    /**
     * 危险色语义（默认关闭，保持既有 20+ 处调用点外观不变）。
     *
     * 存在的理由：删除类动作复用了 chip 形态，但它是**不可逆操作**，
     * 长得和「在用/启用」这类切换 chip 一模一样会让人误判成筛选控件。
     * 开启后文字用对方橙（与长按菜单的 [MenuAction.destructive] 同一语义色），
     * 让"这个点了会删东西"在按下去之前就能看出来。
     */
    destructive: Boolean = false,
) {
    val chipInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(chipInteraction, pressedScale = 0.94f)
            .clip(AppShapes.Chip)
            .background(if (selected) AppColors.MeDim else ChipIdle)
            .then(
                if (outlined) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) {
                            AppColors.MeSoft.copy(alpha = if (AppColors.isDark) 0.45f else 0.30f)
                        } else {
                            AppColors.GlassLine
                        },
                        shape = AppShapes.Chip,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = chipInteraction,
                indication = null, // 涟漪换按压缩放：HyperOS 的克制表达
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTypography.Label,
            color = when {
                destructive -> AppColors.OpponentSoft
                selected -> AppColors.MeSoft
                else -> AppColors.TextSecondary
            },
        )
    }
}

/**
 * 横向滚动的胶囊行 —— 胶囊太多时统一走这里，**不要换行**。
 *
 * ## 为什么不用 FlowRow 而是横向滚动
 *
 * 装备分类有 6 个、品牌标签 16 个。换行会让「同类选项」在视觉上塌成好几排，
 * 既占掉纵向空间，也让人分不清哪排是哪个分类。横向滚动保持**一行 = 一个维度**，
 * 手指一划就看全，配合边缘渐隐暗示"右边还有"。
 *
 * ## 边缘渐隐
 *
 * 只在**确实还能滚**的那一侧画渐隐（[drawWithContent] 里按 scrollState.value 判断）。
 * 两侧都无条件画渐隐的话，滚到头时会出现一圈莫名其妙的灰边。
 *
 * [fadeColor] 必须是**当前所在容器的背景色**：胶囊行一会儿在页面底上、
 * 一会儿在玻璃卡片里，渐隐色给错就会在两个角落留下两块突兀的色斑。
 * 默认 [Color.Transparent] = 不画渐隐（最安全）。
 *
 * 行内的 [SelectableChip] 会**自动获得描边**（通过 [LocalChipOutlined]）——
 * 胶囊在滑动中会横穿各种背景边缘，没有那圈 1dp 线就会糊掉；
 * 调用点不需要手动传 `outlined = true`。
 */
@Composable
fun HorizontalChipRow(
    modifier: Modifier = Modifier,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    fadeColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (fadeColor.alpha <= 0f) return@drawWithContent
                val fade = 18.dp.toPx()
                val max = scrollState.maxValue.toFloat()
                if (max <= 0f) return@drawWithContent
                val offset = scrollState.value.toFloat()
                if (offset > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(fadeColor, fadeColor.copy(alpha = 0f)),
                            startX = 0f,
                            endX = fade,
                        ),
                        size = Size(fade, size.height),
                    )
                }
                if (offset < max) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(fadeColor.copy(alpha = 0f), fadeColor),
                            startX = size.width - fade,
                            endX = size.width,
                        ),
                        size = Size(fade, size.height),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalChipOutlined provides true) {
                content()
            }
        }
    }
}

/** 是否给 [SelectableChip] 画描边。[HorizontalChipRow] 内部自动置 true。 */
private val LocalChipOutlined = androidx.compose.runtime.compositionLocalOf { false }

/** chip / 次级按钮的中性填充。随主题反转：深色底加白、浅色底加黑 */
private val ChipIdle: Color
    get() = AppColors.NeutralFill

/**
 * 数字步进器。
 *
 * 用于时长 / 费用 / 体力这类**有合理范围的小整数**。
 * 比键盘输入快，且不会输错 —— 赛后补录时这点很重要。
 *
 * ## 点数值直接输入
 *
 * ± 步进适合微调；想从 30 直接跳到 180 就太慢了。
 * 中间的数值**可以点**，弹出键盘输入（仍 coerce 到 [range]，不会输出非法值）。
 * 必须用 [Dialog] 独立窗口：本组件常被嵌在可滚动容器里，
 * fillMaxSize 的自绘遮罩会因无界高度约束塌陷成 0（装备页对话框踩过）。
 */
@Composable
fun NumberStepper(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Double>,
    step: Double = 1.0,
    unit: String = "",
    format: (Double) -> String = { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) },
) {
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        var text by remember { mutableStateOf(format(value)) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { editing = false }) {
            SectionCard(
                title = label,
                subtitle = "直接输入数值（范围 ${format(range.start)}–${format(range.endInclusive)}${if (unit.isNotEmpty()) " $unit" else ""}）",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = if (unit.isNotEmpty()) "数值（$unit）" else "数值",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(
                                text = "确认",
                                enabled = text.trim().toDoubleOrNull() != null,
                                onClick = {
                                    val v = text.trim().toDoubleOrNull()
                                    if (v != null) onValueChange(v.coerceIn(range))
                                    editing = false
                                },
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(text = "取消", onClick = { editing = false })
                        }
                    }
                }
            }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppText(label, AppTypography.Body, AppColors.TextSecondary)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StepperButton("−", enabled = value > range.start) {
                onValueChange((value - step).coerceIn(range))
            }
            Box(
                modifier = Modifier
                    .widthIn(min = 62.dp)
                    .clip(AppShapes.Chip)
                    .clickable(onClick = { editing = true })
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = format(value) + if (unit.isNotEmpty()) " $unit" else "",
                    style = AppTypography.CardTitle,
                    color = AppColors.TextPrimary,
                )
            }
            StepperButton("+", enabled = value < range.endInclusive) {
                onValueChange((value + step).coerceIn(range))
            }
        }
    }
}

@Composable
private fun StepperButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val stepperInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(stepperInteraction, pressedScale = 0.92f, haptic = false)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) ChipIdle else AppColors.NeutralFillDisabled)
            .clickable(
                interactionSource = stepperInteraction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTypography.CardTitle,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextTertiary,
        )
    }
}

/**
 * 主行动按钮。在 OLED 黑底上用 HyperOS 蓝实色，
 * 全页只允许出现一个 —— 保证"下一步该做什么"永远没有歧义。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val primaryInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(primaryInteraction)
            .fillMaxWidth()
            .clip(AppShapes.Button)
            .background(if (enabled) AppColors.Me else AppColors.MeDim)
            .clickable(
                interactionSource = primaryInteraction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTypography.CardTitle,
            color = if (enabled) AppColors.OnAccent else AppColors.MeSoft,
        )
    }
}

/** 次要动作（文字按钮） */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val secondaryInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(secondaryInteraction)
            .clip(AppShapes.Button)
            .background(ChipIdle)
            .clickable(
                interactionSource = secondaryInteraction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 14.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTypography.Body,
            color = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary,
        )
    }
}
