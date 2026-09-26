package com.retropro.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.retropro.glass.GlassPanel
import com.retropro.glass.RefractionSpec
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography

/**
 * 长按菜单的一个条目。
 *
 * [destructive] 只影响文字颜色（对方橙）—— 删除这类操作在视觉上**必须和普通项区分**，
 * 否则误触成本只是"看起来和其他项一样"。
 */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * 贴着长按位置弹出的液态玻璃小菜单。
 *
 * ## 为什么用 Popup 而不是底部面板
 *
 * 这个菜单的语义是"针对**这一个**组件"，不是"针对整页"。底部弹出会把对象和操作
 * 在视觉上拉开一整屏的距离，用户需要自己回忆"我刚才长按的是哪个卡片"。
 * 贴着手指弹出的菜单让**对象与操作在同一视野**里，这是长按菜单唯一的价值。
 *
 * ## 定位与边界
 *
 * [PopupPositionProvider] 在**窗口坐标系**里工作，`anchorBounds` 已由 Compose 算好。
 * 两处必须自己兜：
 *  - **下方放不下就翻到上方**（否则贴底的卡片菜单会跑到屏幕外，用户彻底看不到）
 *  - **右侧超出就右对齐 anchor**（长按靠近右边缘时同理）
 * 这两个判断就是官方 `DropdownMenu` 内部做的事，这里手写是因为要复用项目玻璃材质。
 *
 * ## 玻璃与 Popup 的关系（⚠️ 真机需验证）
 *
 * Popup 是**独立窗口**，不在 `AppShell` 的 `layerBackdrop` 子树里。
 * 因此这里的 [GlassPanel] 拿不到页面的 Backdrop 折射源，会顺着降级链
 * 落到 Haze / 纯色。这是可接受的 —— 菜单出现在手指位置、生命周期极短，
 * 折射本就来不及被感知；而 `GlassGuard` 的纯色兜底保证了即便整条玻璃链挂掉，
 * 文字仍在遮罩之上清晰可读（遮罩是 28% 黑，不是实心板）。
 */
@Composable
fun BoxScope.LongPressMenu(
    items: List<MenuAction>,
    /** 长按点的**窗口坐标**（由 [longPressable] 换算好），用于决定菜单弹出方向 */
    anchor: Offset,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val widthPx = with(density) { MENU_WIDTH.roundToPx() }

    // ① 全屏遮罩：挡点击 + 点空白关闭（项目浮层标准写法）
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.TextPrimary.copy(alpha = 0.28f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )

    // ② 菜单本体：Popup 贴长按点
    val provider = remember(anchor, gapPx, widthPx) {
        AnchorPositionProvider(anchor = anchor, gap = gapPx, width = widthPx)
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        GlassPanel(
            key = "longpress.menu",
            shape = AppShapes.CardSmall,
            refraction = RefractionSpec.Subtle,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.widthIn(min = MENU_WIDTH, max = MENU_WIDTH),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                items.forEachIndexed { index, action ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .padding(horizontal = 14.dp)
                                .height(1.dp)
                                .background(AppColors.GlassLine),
                        )
                    }
                    MenuRow(action = action, onDismiss = onDismiss)
                }
            }
        }
    }
}

private val MENU_WIDTH = 172.dp

/**
 * 菜单行：按下缩放 + 轻震动（项目统一点击反馈），无涟漪。
 */
@Composable
private fun MenuRow(action: MenuAction, onDismiss: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.97f)
            .clip(AppShapes.CardSmall)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    // 先关菜单再执行：破坏性操作后面还要弹二次确认，
                    // 菜单留在屏幕上会和新弹窗叠成两层浮层
                    onDismiss()
                    action.onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        AppText(
            text = action.label,
            style = AppTypography.Body,
            color = if (action.destructive) AppColors.OpponentSoft else AppColors.TextPrimary,
            // 菜单固定宽 172dp，而破坏性项现在带对象名（如「删除「尤尼克斯 天斧 100ZZ」」）——
            // 不限行会换行把该项撑成两行高，菜单高度随对象名长短跳动。
            // 截断成一行 + 省略号，保证每项等高。
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 把菜单锚在长按点上，并做**屏幕边界翻转**。
 *
 * [anchor] 已经是**窗口坐标**（见 [longPressable] 的换算），
 * 因此这里**不能再加 `anchorBounds`** —— 加了会偏移出半个屏幕。
 *
 * ⚠️ 接口签名照 javap 核实（`PopupPositionProvider` 只有四个参数：
 * `IntRect / IntSize / LayoutDirection / 返回 IntOffset`，**没有 Density**）——
 * 凭记忆写会多一个 Density 参数，编译期直接报 `overrides nothing`。
 */
private class AnchorPositionProvider(
    private val anchor: Offset,
    private val gap: Int,
    private val width: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val tapX = anchor.x.toInt()
        val tapY = anchor.y.toInt()

        // 水平：默认左对齐长按点；右边放不下就右对齐长按点
        val x = if (tapX + width + gap <= windowSize.width) {
            tapX
        } else {
            (tapX - popupContentSize.width).coerceAtLeast(gap)
        }

        // 垂直：默认出现在长按点下方；下方放不下就翻到上方
        val below = tapY + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (tapY - gap - popupContentSize.height).coerceAtLeast(gap)
        }

        return IntOffset(x, y)
    }
}

/**
 * 破坏性操作的二次确认。
 *
 * 用 `Dialog` 独立窗口：调用点常嵌在 LazyColumn item 里，高度约束无界，
 * 自绘遮罩的 `fillMaxSize` 会塌陷成 0（装备页对话框踩过同样的坑）。
 *
 * ## 统一外观约定（所有调用点必须遵守）
 *
 * 确认框的**视觉与措辞各调用点一律不要自己发挥** —— 三处删除（场次 / 装备 / 指标卡）
 * 曾经各写各的，结果是标题标点不一致（有的带书名号有的不带）、
 * "能不能恢复"的说法三种（"无法恢复" / "此操作不可撤销" / 干脆不提）。
 * 用户对这类弹窗的认知应该只建立**一次**。
 *
 * 因此：
 * - **标题格式固定为 `动作「对象名」？`** —— 对象名一律加书名号，
 *   调用方把动作词通过 [confirmText] 传进来即可，不要自己在 title 里拼标点。
 * - **可否撤销由 [reversible] 表达**，不写进 [message] ——
 *   组件会统一拼出"此操作不可撤销"或"之后可以恢复"的尾句。
 *   [message] 只负责说**连带影响**（会一起删掉什么、哪个统计数字会变小）。
 * - [confirmText] 默认"删除"；清空类操作用"清空"。
 *
 * @param title 标题，格式 `删除「XXX」？` / `清空「XXX」记录？`
 * @param message **只写连带影响**，不要写"不可撤销"之类的通用风险话术（由 [reversible] 统一生成）
 * @param confirmText 确认按钮文案，也是标题里的动作词来源（"删除" / "清空"）
 * @param reversible 操作是否可从界面恢复（如指标卡可重新添加）。
 *   `false` 会在正文末尾统一缀上不可撤销提示。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "删除",
    reversible: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassPanel(
            key = "confirm.dialog",
            shape = AppShapes.Card,
            refraction = RefractionSpec.Subtle,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                AppText(title, AppTypography.CardTitle, AppColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                // 可否撤销的措辞由组件统一生成，调用点只描述连带影响 ——
                // 这样"不可撤销"的用词在全 App 只出现一次，不会各处走样。
                AppText(
                    text = if (reversible) {
                        "$message 之后可以恢复。"
                    } else {
                        "$message 此操作不可撤销。"
                    },
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        ConfirmButton(
                            text = confirmText,
                            fill = AppColors.Opponent,
                            textColor = Color.White,
                            onClick = {
                                onDismiss()
                                onConfirm()
                            },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        // ⚠️ 这里**不要**换成 uikit 的 SecondaryButton。
                        // 它的填充是 ChipIdle = NeutralFill（深色底只有 8% 白），垫在玻璃上
                        // 几乎不可见 → 屏幕上只剩左键有实心，两个胶囊看起来一大一小、长短不一。
                        // 确认框只有这一个 Cancel 位，就地同构实现（同 padding、同形状、同文字档）
                        // 保证两键尺寸一致，别为了复用拉出参数把 SecondaryButton 复杂化。
                        ConfirmButton(
                            text = "取消",
                            fill = AppColors.NeutralFill,
                            textColor = AppColors.TextSecondary,
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 确认框按钮：[confirmText]（危险色实心）与"取消"（中性实心）**同构**。
 *
 * 唯一的差别只有 [fill] 和 [textColor]，其余（形状、内边距、文字档、按压反馈）完全一致 ——
 * 外观一致性靠"同一份实现"保证，而不是靠两处各写一遍再对齐数值。
 *
 * 垂直 padding 由调用点的 `weight(1f)` + 等宽容器补足水平方向，
 * 所以这里**不要**再加 horizontal padding：会吃掉宽度让两键不等宽。
 */
@Composable
private fun ConfirmButton(
    text: String,
    fill: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(AppShapes.Button)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTypography.Body,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * 长按手势修饰符 —— 项目统一入口，回报**长按点的窗口坐标**。
 *
 * ## 为什么上报窗口坐标而不是组件内坐标
 *
 * [LongPressMenu] 挂在页面根 `Box` 上，`Popup` 拿到的 `anchorBounds` 是**根容器**的矩形。
 * 若这里上报"相对被长按卡片"的偏移，两者相加得到的坐标就错了
 * （卡片的局部原点 ≠ 根容器的局部原点）。统一换算到窗口坐标后，
 * `anchorBounds.left + anchor.x` 才是真正的长按点。
 *
 * ## 为什么用 `composed`
 *
 * 需要 `remember` 一个跨回调存活的原点变量（`onGloballyPositioned` 布局期写入、
 * 长按时读取），以及 `LocalHapticFeedback`。`Modifier` 扩展本身不是 `@Composable`，
 * 拿不到这些作用域，所以用 `composed` 包一层。`pointerInput` 的 key 用 [key]，
 * 只有依赖变化才重建手势检测器。
 *
 * ## 长按震感
 *
 * 用 `HapticFeedbackType.LongPress`（**不是** `TextHandleMove`）——
 * 语义上这是"长按手势成立"的反馈档，系统触感比轻点档更实、更短促，
 * 对应"菜单即将弹出"这件事。注意与 [pressScale] 的震动**不冲突**：
 * 后者是按下瞬间的极轻一档，这里是按住 350ms 后成立的那一下，
 * 两下分属不同阶段，叠起来正好是"按下去 → 定住 → 弹出"的节奏。
 */
fun Modifier.longPressable(
    key: Any?,
    onTap: () -> Unit,
    onLongPressAt: (Offset) -> Unit,
    haptic: Boolean = true,
): Modifier = this.composed {
    // 组件窗口原点：布局期由 onGloballyPositioned 写入，手势回调读取。
    // 布局一定早于手势，所以读到的是当前值（卡片滚动后也会被重新布局更新）。
    val origin = remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    this
        .onGloballyPositioned { coords ->
            origin.value = coords.positionInWindow()
        }
        .pointerInput(key) {
            detectTapAndLongPress(
                onTap = onTap,
                onLongPressAt = { local ->
                    if (haptic) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // 局部偏移 → 窗口坐标
                    onLongPressAt(origin.value + local)
                },
            )
        }
}
