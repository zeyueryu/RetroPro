package com.retropro.feature.stats

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.db.StatsOverview
import com.retropro.data.model.DashboardCard
import com.retropro.glass.GlassPanel
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppText
import com.retropro.uikit.ConfirmDialog
import com.retropro.uikit.HorizontalChipRow
import com.retropro.uikit.MiuixPopupScope
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import com.retropro.uikit.LongPressMenu
import com.retropro.uikit.MenuAction
import com.retropro.uikit.SectionCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.longPressable
import com.retropro.uikit.pressScale
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.Formatters
import com.retropro.data.db.CategoryCost
import com.retropro.feature.equipment.GearCat

/**
 * 统计页。
 *
 * ## 指标口径（严格按设计方案 §8）
 *
 * | 指标 | 定义 |
 * |---|---|
 * | **胜利局数** | `games.result = 0` 的计数。注意是**局**不是场 —— 羽毛球三局两胜，赢 2 局才是赢 1 场 |
 * | **复盘局数** | 至少有 1 条逐球心得的**局**数。这是本 App 独有的指标：普通记分 App 只有比分，没有"想过没有" |
 * | 常丢分在哪 | 我方失分球的 reasonTag 频次 Top 5 |
 *
 * 页面上把「胜利局数 / 复盘局数」并排放在最上面，是因为这两个数正是用户提出
 * 想要的两个自定义指标，其余数字都是附属。
 */
@Composable
fun StatsScreen(vm: StatsViewModel, onOpenOpponentStats: () -> Unit = {}) {
    val overview by vm.overview.collectAsStateWithLifecycle()
    val reasons by vm.topLossReasons.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle(initialValue = emptyList())
    val opponentStats by vm.opponentStats.collectAsStateWithLifecycle(initialValue = emptyList())
    val gearCosts by remember {
        com.retropro.di.AppGraph.equipment.observeGearCosts()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // 长按菜单：指标卡 → 显隐/排序/删除；固定卡 → 折叠
    var metricMenu by remember { mutableStateOf<DashboardCard?>(null) }
    var fixedMenu by remember { mutableStateOf<String?>(null) }
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 删除二次确认：只留 key 与展示名（菜单已关，不能再依赖 metricMenu）
    var pendingRemove by remember { mutableStateOf<Pair<DashboardCard, String>?>(null) }
    // 固定卡的折叠态（会话级，不落盘：它们是固定区块，不需要跨启动记忆）
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    // 外面套 MiuixPopupScope：`OverlayIconCascadingDropdownMenu` 依赖 Scaffold 提供的
    // MiuixPopupHost 渲染弹窗内容，不在 Scaffold 里的话点 FAB 毫无反应（且不报错，
    // 很容易误判成手势没接上）。这里包在**最外层**而不是只包 FAB：
    // 弹窗宿主需要是整页的祖辈，只包 FAB 那块会让 Scaffold 的内容区 fillMaxSize
    // 把 FAB 挤到屏幕中心，破坏右上角定位。
    MiuixPopupScope(modifier = Modifier.fillMaxSize()) {
        // 根节点用 Box：FAB 与长按菜单都要盖在整页之上
        Box(Modifier.fillMaxSize()) {
        Column(
        modifier = Modifier
            .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 标题行（FAB 已改为右下角常驻悬浮，见下方 Box 末尾）
        Box(Modifier.fillMaxWidth()) {
            Column {
                AppText("统计", AppTypography.Display, AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                AppText(
                    text = "胜利与复盘都按「局」统计",
                    style = AppTypography.Caption,
                    color = AppColors.TextTertiary,
                )
            }
        }

        val data = overview
        if (data == null || data.sessionCount == 0) {
            SectionCard(
                title = "还没有数据",
                subtitle = "在「记录」页开一场，或者补录一场赛后记录",
            ) {
                AppText(
                    text = "统计会在你记下第一场球之后自动出现。",
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary,
                )
            }
            return@Column
        }

        SectionCard(
            title = "总量",
            modifier = Modifier.longPressable(
                key = "fixed.total",
                onTap = {},
                onLongPressAt = { at ->
                    menuAnchor = at
                    fixedMenu = "fixed.total"
                },
            ),
        ) {
            CollapsibleBody(key = "fixed.total", collapsed = collapsed) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatLine("场次", "${data.sessionCount} 场")
                    StatLine("已打出比分的局", "${data.playedGames} 局")
                    StatLine("逐球记录", "${data.rallyCount} 球")
                    StatLine("已写心得", "${data.reviewedRallyCount} 球")
                    StatLine("累计花费", Formatters.money(data.totalFee))
                    StatLine(
                        label = "复盘率",
                        value = if (data.rallyCount == 0) {
                            "—"
                        } else {
                            "%.0f%%".format(data.reviewedRallyCount * 100f / data.rallyCount)
                        },
                    )
                }
            }
        }

        // ---- 指标卡：常驻统计信息（不藏在任何可折叠外壳里，永远可见）
        // 显隐 / 排序 / 增删等控制项已收进右上角 FAB 的级联下拉菜单（见 StatsCustomizeMenu），
        // 也可以**长按任意指标卡**直接在卡片上操作（同一个能力的两个入口）
        cards.filter { it.visible }
            .sortedBy { it.orderIndex }
            .mapNotNull { card -> MetricKey.of(card.metricKey)?.let { card to it } }
            .chunked(2)
            .forEach { rowCards ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    rowCards.forEach { (card, metric) ->
                        SectionCard(
                            title = metric.label,
                            modifier = Modifier
                                .weight(1f)
                                .longPressable(
                                    key = card.id,
                                    onTap = {},
                                    onLongPressAt = { at ->
                                        menuAnchor = at
                                        metricMenu = card
                                    },
                                ),
                        ) {
                            Column {
                                AppText(metric.valueOf(data), AppTypography.Display, metric.accent)
                                Spacer(Modifier.height(4.dp))
                                AppText(
                                    text = "第 ${card.orderIndex + 1} 位",
                                    style = AppTypography.Caption,
                                    color = AppColors.TextSecondary,
                                )
                            }
                        }
                    }
                    // 单数行补一个占位，让卡片保持等宽
                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                }
            }

        SectionCard(
            title = "对手局数统计",
            subtitle = "交手最多的前三名对手 · 局按真实比分计",
            // 短按进对手统计，长按弹折叠菜单 —— 两者由同一个手势识别器负责，
            // 不能再叠一个 clickable（两个识别器会互相抢事件）
            modifier = Modifier.longPressable(
                key = "fixed.opponent",
                onTap = onOpenOpponentStats,
                onLongPressAt = { at ->
                    menuAnchor = at
                    fixedMenu = "fixed.opponent"
                },
            ),
        ) {
            val top3 = opponentStats.take(3)
            if (top3.isEmpty()) {
                AppText(
                    text = "打完比赛后，这里会显示交手最多的对手。",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            } else {
                CollapsibleBody(key = "fixed.opponent", collapsed = collapsed) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        top3.forEachIndexed { index, stat ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AppText(
                                        "No." + (index + 1),
                                        AppTypography.Body,
                                        AppColors.MeSoft,
                                    )
                                    AppText(
                                        stat.name?.ifBlank { "未知对手" } ?: "未知对手",
                                        AppTypography.Body,
                                        AppColors.TextPrimary,
                                    )
                                }
                                AppText(
                                    stat.total.toString() + " 局（" + stat.myWins + " 胜）",
                                    AppTypography.Body,
                                    AppColors.TextSecondary,
                                )
                            }
                        }
                        AppText(
                            text = "点卡片查看全部对手 ›",
                            AppTypography.Caption,
                            AppColors.TextTertiary,
                        )
                    }
                }
            }
        }

        SectionCard(
            title = "装备花费",
            subtitle = "按在用装备的购入价合计（球鞋 / 球衣 / 手胶）",
            modifier = Modifier.longPressable(
                key = "fixed.gear",
                onTap = {},
                onLongPressAt = { at ->
                    menuAnchor = at
                    fixedMenu = "fixed.gear"
                },
            ),
        ) {
            if (gearCosts.isEmpty()) {
                AppText(
                    text = "在装备页添加球鞋、球衣、手胶后，这里会显示各类合计。",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            } else {
                CollapsibleBody(key = "fixed.gear", collapsed = collapsed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GearCat.entries.forEach { cat ->
                            val c = gearCosts.firstOrNull { it.category == cat.code }
                            Box(Modifier.weight(1f)) {
                                Column(
                                    Modifier
                                        .background(AppColors.NeutralFill, AppShapes.Chip)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    AppText(cat.label, AppTypography.Label, AppColors.TextTertiary)
                                    Spacer(Modifier.height(2.dp))
                                    AppText(
                                        text = if (c != null && c.total > 0) "¥${c.total.toLong()}" else "—",
                                        AppTypography.CardTitle,
                                        AppColors.TextPrimary,
                                    )
                                    AppText(
                                        text = "${c?.count ?: 0} 件",
                                        AppTypography.Caption,
                                        AppColors.TextTertiary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionCard(
            title = "常丢分在哪",
            subtitle = if (reasons.isEmpty()) "给失分球打上原因标签后，这里会显示 Top 5" else "按出现次数排序",
            modifier = Modifier.longPressable(
                key = "fixed.reasons",
                onTap = {},
                onLongPressAt = { at ->
                    menuAnchor = at
                    fixedMenu = "fixed.reasons"
                },
            ),
        ) {
            if (reasons.isEmpty()) {
                AppText(
                    text = "目前没有失分原因记录。",
                    style = AppTypography.Caption,
                    color = AppColors.TextTertiary,
                )
            } else {
                CollapsibleBody(key = "fixed.reasons", collapsed = collapsed) {
                    // 条形长度按最大值归一 —— 不引 Vico，这个场景一个 Box 就够
                    val max = reasons.maxOfOrNull { it.count } ?: 1
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        reasons.forEach { tc ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    AppText(tc.reasonTag, AppTypography.Body, AppColors.TextPrimary)
                                    AppText("${tc.count} 球", AppTypography.Label, AppColors.OpponentSoft)
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .background(AppColors.NeutralFill, AppShapes.Chip),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(tc.count.toFloat() / max)
                                            .height(6.dp)
                                            .background(AppColors.Opponent.copy(alpha = 0.55f), AppShapes.Chip),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ---- 自定义入口：右上角悬浮的液态玻璃圆钮（竖向三点）
    // 用 MIUIX 官方 `OverlayIconCascadingDropdownMenu`：它自带「图标按钮 + 级联下拉菜单」，
    // 展开时菜单从按钮位置形变长出并带小三角指向锚点 —— 与目标视觉一致。
    // 放在 Column 之后 = 绘制在内容上层；不随滚动移动（在根 Box 里，不在滚动的 Column 里）。
    //
    // ⚠️ 定位必须由**调用点**在 BoxScope 里用 Modifier.align 完成：
    // `align` 是 BoxScope 的扩展，不能作为 Modifier 参数传进子 composable 再应用。
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(end = 20.dp, top = 8.dp),
    ) {
        StatsCustomizeMenu(
            cards = cards,
            onSetVisible = { card, v -> vm.setVisible(card, v) },
            onMoveUp = { vm.moveUp(it) },
            onMoveDown = { vm.moveDown(it) },
            onAdd = { vm.addCard(it) },
            // 「删除此卡」不做二次确认：菜单本身就是显式动作，
            // 而且删错了可以从「添加指标卡」里一键加回来（非破坏性）。
        onRemove = { vm.remove(it) },
        onShowAll = { vm.showAll() },
        onReset = { vm.resetToDefault() },
    )
    }

    // ---- 长按指标卡：直接在该卡上做显隐 / 排序 / 删除
    metricMenu?.let { card ->
        val metric = MetricKey.of(card.metricKey)
        if (metric != null) {
            LongPressMenu(
                items = listOf(
                    MenuAction(
                        label = if (card.visible) "隐藏此卡" else "显示此卡",
                        onClick = { vm.setVisible(card, !card.visible) },
                    ),
                    MenuAction(label = "上移", onClick = { vm.moveUp(card) }),
                    MenuAction(label = "下移", onClick = { vm.moveDown(card) }),
                    MenuAction(
                        label = "删除「${metric.label}」卡",
                        destructive = true,
                        onClick = { pendingRemove = card to metric.label },
                    ),
                ),
                anchor = menuAnchor,
                onDismiss = { metricMenu = null },
            )
        }
    }

    // ---- 长按固定卡：折叠 / 展开（固定区块没有顺序与显隐语义，只提供收起）
    fixedMenu?.let { key ->
        val isCollapsed = key in collapsed
        LongPressMenu(
            items = listOf(
                MenuAction(
                    label = if (isCollapsed) "展开此卡" else "折叠此卡",
                    onClick = {
                        collapsed = if (isCollapsed) collapsed - key else collapsed + key
                    },
                ),
            ),
            anchor = menuAnchor,
            onDismiss = { fixedMenu = null },
        )
    }

    // ---- 删除指标卡二次确认
    pendingRemove?.let { (card, label) ->
        ConfirmDialog(
            title = "删除「$label」卡？",
            message = "这张指标卡会从统计页移除，不影响任何记录与比分数据。",
            // 可恢复：删错了能从「添加指标卡」一键加回来，所以走 reversible 分支
            reversible = true,
            onConfirm = { vm.remove(card) },
            onDismiss = { pendingRemove = null },
        )
    }
    }   // 根 Box 闭
    }   // MiuixPopupScope 闭
}

/**
 * 固定卡的折叠壳：收起时整体隐去，展开时正常渲染。
 *
 * 用 [androidx.compose.animation.AnimatedVisibility] 而不是直接 `if` ——
 * 折叠/展开是用户主动触发的动作，需要一帧动画说明"东西去哪了"，
 * 否则卡片会突然跳变，看起来像 bug。
 */
@Composable
private fun CollapsibleBody(
    key: String,
    collapsed: Set<String>,
    content: @Composable () -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = key !in collapsed,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        content()
    }
}

/**
 * 右上角自定义入口 —— **MIUIX 官方级联下拉菜单**，锚点是一个液态玻璃圆钮（竖向三点）。
 *
 * ## 为什么用官方组件而不是自研
 *
 * 需求是「贴着 FAB 从按钮位置形变长出的菜单 + 小三角指向锚点 + 二级右侧滑入」。
 * MIUIX 的 `OverlayIconCascadingDropdownMenu` 恰好就是这套：
 *  - 自带 enter 形变（内部 `enterFraction` / `enterAlpha` / `primaryScale` / `maskAlpha`）
 *  - 自带 caret 小三角与两层级联（子菜单从侧边滑入、父项箭头旋转）
 *  - 级联数据模型就是 `DropdownItem.children`，不需要自己搭二级面板
 * 自研这两件事（尤其形变曲线与 caret 定位）成本高且容易和官方手感不一致。
 *
 * ## ⚠️ 两个约束
 *
 * 1. **必须套在 `Scaffold` 里**（本文件用 [MiuixPopupScope] 包了一层）——
 *    该组件依赖 Scaffold 提供的 `MiuixPopupHost` 渲染弹窗内容，否则弹窗不渲染。
 * 2. **级联深度上限 2 层**：二级项不能再有 `children`，更深的树会被静默忽略。
 *    所以这里一级 = 4 个入口，二级 = 具体动作，正好两层。
 *
 * ## 菜单结构（一级四项）
 *
 * | 一级 | 二级 |
 * |---|---|
 * | 指标卡管理 | 每个已添加指标 → 显示/隐藏 · 上移 · 下移 · 删除 |
 * | 添加指标卡 | 尚未添加的指标（点击即加） |
 * | 显示全部指标 | 无二级，点击即生效 |
 * | 重置为默认 | 无二级，点击即生效（把卡片集恢复成默认排序与显隐） |
 */
@Composable
private fun StatsCustomizeMenu(
    cards: List<DashboardCard>,
    onSetVisible: (DashboardCard, Boolean) -> Unit,
    onMoveUp: (DashboardCard) -> Unit,
    onMoveDown: (DashboardCard) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (DashboardCard) -> Unit,
    onShowAll: () -> Unit,
    onReset: () -> Unit,
) {
    val sorted = remember(cards) { cards.sortedBy { it.orderIndex } }
    val existing = remember(cards) { cards.map { it.metricKey }.toSet() }
    val available = remember(existing) { MetricKey.entries.filter { it.key !in existing } }

    // 菜单展开态由组件自己记（父层不需要知道），只通过 onExpandedChange 外抛给需要的场景
    var expandedState by remember { mutableStateOf(false) }

    val entries = remember(sorted, available) {
        listOf(
            DropdownEntry(
                items = buildList {
                    // ---- 一级①：指标卡管理（每个指标一个二级子菜单）
                    if (sorted.isEmpty()) {
                        add(
                            DropdownItem(
                                text = "指标卡管理",
                                enabled = false,
                                summary = "还没有指标卡",
                            ),
                        )
                    } else {
                        add(
                            DropdownItem(
                                text = "指标卡管理",
                                summary = "${sorted.size} 张",
                                children = sorted.mapNotNull { card ->
                                    val metric = MetricKey.of(card.metricKey) ?: return@mapNotNull null
                                    DropdownItem(
                                        text = metric.label,
                                        // 已隐藏的指标在二级里也标出来，避免"看不到却以为没加"
                                        summary = if (card.visible) null else "已隐藏",
                                        selected = card.visible,
                                        children = listOf(
                                            DropdownItem(
                                                text = if (card.visible) "隐藏" else "显示",
                                                onClick = { onSetVisible(card, !card.visible) },
                                            ),
                                            DropdownItem(text = "上移", onClick = { onMoveUp(card) }),
                                            DropdownItem(text = "下移", onClick = { onMoveDown(card) }),
                                            DropdownItem(
                                                text = "删除",
                                                // 这里**有意**不弹二次确认：
                                                // 删除指标卡完全可恢复（一级「添加指标卡」一键加回），
                                                // 且菜单项本身就是用户主动点开的显式动作。
                                                // 判定标准与 ConfirmDialog 的 reversible 参数同源 ——
                                                // 不可恢复的才需要拦一道。
                                                onClick = { onRemove(card) },
                                            ),
                                        ),
                                    )
                                },
                            ),
                        )
                    }

                    // ---- 一级②：添加指标卡
                    add(
                        DropdownItem(
                            text = "添加指标卡",
                            enabled = available.isNotEmpty(),
                            summary = if (available.isEmpty()) "已全部添加" else "${available.size} 个可加",
                            children = available.map { metric ->
                                DropdownItem(text = metric.label, onClick = { onAdd(metric.key) })
                            },
                        ),
                    )

                    // ---- 一级③：显示全部指标（无二级，直接生效）
                    val hiddenCount = sorted.count { !it.visible }
                    add(
                        DropdownItem(
                            text = "显示全部指标",
                            enabled = hiddenCount > 0,
                            summary = if (hiddenCount == 0) "没有隐藏的卡" else "有 $hiddenCount 张隐藏",
                            onClick = onShowAll,
                        ),
                    )

                    // ---- 一级④：重置为默认（无二级，直接生效）
                    add(
                        DropdownItem(
                            text = "重置为默认",
                            summary = "恢复默认卡片与顺序",
                            enabled = cards.isNotEmpty(),
                            onClick = onReset,
                        ),
                    )
                },
            ),
        )
    }

    // Scaffold 壳已在页面最外层套好（见函数开头），这里直接用官方组件。
    // 不能再套一层 MiuixPopupScope：里层 Scaffold 的内容区会撑满，破坏右上角定位。
    OverlayIconCascadingDropdownMenu(
        entries = entries,
        // 不用 MIUIX 的 IconButton 外观：传透明背景，内容自绘成项目液态玻璃圆钮
        backgroundColor = Color.Transparent,
        cornerRadius = 23.dp,
        minHeight = 46.dp,
        minWidth = 46.dp,
        collapseOnSelection = true,
        onExpandedChange = { expandedState = it },
    ) {
        StatsMenuAnchor(expanded = expandedState)
    }
}

/**
 * 菜单锚点 —— 项目风格的**液态玻璃圆形按钮**（竖向三个点）。
 *
 * ⚠️ 三个非显然点：
 *  1. `shape` 必须用 `RoundedCornerShape(percent = 50)` 而不是 `CircleShape` ——
 *     `GlassPanel` 的 shape 参数是 `RoundedCornerShape`，Backdrop 的 lens 与 Haze 的
 *     shape() 都只接受可计算圆角的形状。percent=50 在正方形上即正圆。
 *  2. 小面积控件用 `RefractionSpec.Subtle`（官方取值：blur 2dp + lens 10/18）——
 *     大折射在小圆钮上会糊掉几何。
 *  3. **不要自己挂 clickable**：点击由外层 `OverlayIconCascadingDropdownMenu` 处理，
 *     这里再挂一个手势识别器会和它抢事件（项目已踩过 `clickable` + `longPressable` 的坑）。
 *
 * [expanded] 时三个点整体转 90°（竖向 → 横向）作为状态提示。旋转值只在
 * `graphicsLayer` 的 lambda 里读，组合期不读 → **旋转过程零重组**。
 */
@Composable
private fun StatsMenuAnchor(expanded: Boolean) {
    val spin = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "fabSpin",
    )
    GlassPanel(
        key = "stats.customize.fab",
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        refraction = com.retropro.glass.RefractionSpec.Subtle,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(46.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { rotationZ = spin.value },
            verticalArrangement = Arrangement.spacedBy(3.5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(AppColors.TextSecondary),
                )
            }
        }
    }
}


@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(label, AppTypography.Body, AppColors.TextSecondary)
        AppText(value, AppTypography.CardTitle, AppColors.TextPrimary)
    }
}
