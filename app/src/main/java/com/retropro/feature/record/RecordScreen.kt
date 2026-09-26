package com.retropro.feature.record

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.db.SessionListItem
import com.retropro.data.model.MatchMode
import com.retropro.data.repository.QuickStart
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.pressScale
import com.retropro.uikit.AppText
import com.retropro.uikit.ConfirmDialog
import com.retropro.uikit.LongPressMenu
import com.retropro.uikit.MenuAction
import com.retropro.uikit.longPressable
import com.retropro.glass.GlassPanel
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.DailyQuotes
import com.retropro.util.Formatters
import com.retropro.util.AppPrefs
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Tasks

/**
 * 时间轴 —— App 的主页。
 *
 * ## 布局严格按原型（原型预览.html 第 01 屏）
 *
 * ```
 * 9月22日 周二
 * 本月已打 11 场
 * ┌──────────┬──────────┐
 * │ 开始计分  │ 快速记录  │   ← 两个等宽快捷按钮
 * │ 横屏计分板│ 语音一句话│
 * └──────────┴──────────┘
 * 最近记录            全部 ›
 * ┌──┬───────────────┐
 * │22│城东体育馆 · 3号场  胜│
 * │SEP│21-18 19-21 21-15 │
 * │  │vs 老陈 120分钟 ¥45│
 * └──┴───────────────────┘
 * ```
 *
 * ## 「开始计分」只有一步
 *
 * 点下就建出 场次 + 对阵 + 第 1 局 并**直接进横屏计分板**。
 * 场馆/对手/费用全部赛后补录 —— 球场上每多一个字段都是劝退，
 * 与设计方案的「默认值推导」一致。
 *
 * ## 场次行：左侧日期块
 *
 * 原型用「大数字日 + 月份缩写」的日期块而不是一行日期文字：
 * 时间轴的意义就在于**扫一眼按天对齐**，日期块让多行记录天然形成一条竖直时间线。
 */
@Composable
fun RecordScreen(
    vm: RecordViewModel,
    onOpenSession: (Long) -> Unit,
    onOpenCreateForm: () -> Unit,
    onStartedQuickMatch: (QuickStart) -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    // 长按菜单：记录哪一场被长按 + 长按点（贴手指弹出）
    var menuFor by remember { mutableStateOf<SessionListItem?>(null) }
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // 删除二次确认：只记 id 与展示名，确认框关闭后菜单已关，不能再依赖 menuFor
    var pendingDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
        contentPadding = PaddingValues(
        start = 20.dp,
        end = 20.dp,
        // 顶部留白放在滚动内容里（不是视口避让）：静止时标题在时钟下方，滚动时从状态栏下穿过
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp,
        bottom = navBarClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Hero：日期 + 每日格言 + 本月已打 N 场（大字头，视觉锚点）
        item {
            Column {
                AppText(todayLabel(), AppTypography.Title, AppColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                // 每日格言：自定义优先，否则预设按日轮换（判定收在 DailyQuotes.forToday）
                // 24sp 大字头（Lead = title2）：Hero 区的视觉锚点。
                // 档位选择靠"往上找现成档"而不是就地放大 —— 见 AppTypography 的档位字号表。
                // 改后层级：数字 28sp > 格言 24sp > 日期 20sp，是刻意保留的递进。
                AppText(
                    text = DailyQuotes.forToday(AppPrefs.Live.dailyQuote),
                    style = AppTypography.Lead,
                    color = AppColors.TextPrimary,
                    // 预设库最长 20 字，24sp 在约 280dp 有效宽度下占 2 行；
                    // 留到 4 行是为容纳系统字体缩放到 1.5 倍的情况；
                    // 仍设上限是为了挡住用户在设置页粘贴的长文（输入框没有长度限制）
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                MonthCountLine(count = monthCount(sessions))
            }
        }

        // ---- 两个快捷按钮（等宽）
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickButton(
                    icon = MiuixIcons.Regular.Notes,
                    title = "开始计分",
                    detail = "横屏计分板",
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 一句话都不问：场馆/对手赛后补录
                        vm.startQuickMatch(venue = "", opponentName = null, mode = MatchMode.SINGLES) { onStartedQuickMatch(it) }
                    },
                )
                QuickButton(
                    icon = MiuixIcons.Regular.Tasks,
                    title = "快速记录",
                    detail = "语音一句话",
                    primary = false,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCreateForm,
                )
            }
        }

        // ---- 分区头
        item {
            SectionHeader(left = "最近记录", right = if (sessions.size > 3) "全部 ›" else null)
        }

        if (sessions.isEmpty()) {
            item { EmptyTimeline() }
        }

        items(sessions, key = { it.id }) { item ->
            SessionRow(
                item = item,
                onClick = { onOpenSession(item.id) },
                onLongPress = { at ->
                    menuAnchor = at
                    menuFor = item
                },
            )
        }
    }

    // ---- 长按菜单：贴长按位置弹出
    menuFor?.let { item ->
        LongPressMenu(
            items = listOf(
                MenuAction(
                    label = "打开详情",
                    onClick = {
                        menuFor = null
                        onOpenSession(item.id)
                    },
                ),
                MenuAction(
                    label = "编辑",
                    // 编辑表单在详情页内（SessionInfoCard / MatchCard 的内联表单），
                    // 这里不重复实现一套，直接跳过去点「编辑信息」
                    onClick = {
                        menuFor = null
                        onOpenSession(item.id)
                    },
                ),
                MenuAction(
                    label = "删除「${item.venue.ifBlank { item.type.label }}」",
                    destructive = true,
                    onClick = {
                        menuFor = null
                        pendingDelete = item.id to item.venue.ifBlank { item.type.label }
                    },
                ),
            ),
            anchor = menuAnchor,
            onDismiss = { menuFor = null },
        )
    }

    // ---- 删除二次确认
    pendingDelete?.let { (id, name) ->
        ConfirmDialog(
            title = "删除「$name」？",
            message = "这场球的比分、逐球记录与心得都会一起删掉。",
            onConfirm = { vm.delete(id) },
            onDismiss = { pendingDelete = null },
        )
    }
    } // 根 Box 闭
}

// ---------------------------------------------------------------- Hero

/** 「9月22日 周二」—— 含星期，按周回忆比按数字直观 */
private fun todayLabel(): String {
    val d = LocalDate.now()
    val week = when (d.dayOfWeek.value) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; else -> "周日"
    }
    return "${d.monthValue} 月 ${d.dayOfMonth} 日 $week"
}

/** 本月场次：从已有记录里数，不额外查询 */
private fun monthCount(sessions: List<SessionListItem>): Int {
    val now = LocalDate.now()
    return sessions.count {
        val d = Instant.ofEpochMilli(it.date).atZone(ZoneId.systemDefault()).toLocalDate()
        d.year == now.year && d.month == now.month
    }
}

/**
 * 「本月已打 N 场」：数字比文字大一号。
 *
 * 这里用 `BasicText` 而不是 [AppText]，因为要把一行拆成两种字号拼接。
 */
@Composable
private fun MonthCountLine(count: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText("本月已打 ", style = AppTypography.Title.copy(color = AppColors.TextPrimary))
        BasicText(
            text = count.toString(),
            style = AppTypography.Title.copy(
                color = AppColors.MeSoft,
                fontSize = AppTypography.Title.fontSize * 2f,
            ),
        )
        BasicText(" 场", style = AppTypography.Title.copy(color = AppColors.TextPrimary))
    }
}

// ---------------------------------------------------------------- 快捷按钮

/**
 * 快捷按钮：主按钮（蓝底白字）+ 次按钮（玻璃底，深字）。
 *
 * 两个按钮等宽、带图标 + 标题 + 说明三行 —— 它们是球场上仅有的两个入口，
 * 必须一眼可辨、一触即达。
 */
@Composable
private fun QuickButton(
    icon: ImageVector,
    title: String,
    detail: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = if (primary) AppColors.Me else AppColors.SurfaceFallback.copy(alpha = 0.72f)
    val titleColor = if (primary) AppColors.OnAccent else AppColors.TextPrimary
    val detailColor = if (primary) AppColors.OnAccent.copy(alpha = 0.85f) else AppColors.TextSecondary

    val quickInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(quickInteraction)
            .clip(AppShapes.Card)
            .background(bg)
            .clickable(
                interactionSource = quickInteraction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(
                    color = if (primary) Color.White.copy(alpha = 0.22f) else AppColors.MeDim,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(titleColor),
            )
        }
        Spacer(Modifier.height(6.dp))
        // 首页两大入口用 Title 级字号：这是全页视觉权重最高的两块
        AppText(title, AppTypography.Title, titleColor)
        AppText(detail, AppTypography.Body, detailColor)
    }
}

// ---------------------------------------------------------------- 分区头

@Composable
private fun SectionHeader(left: String, right: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(left, AppTypography.CardTitle, AppColors.TextPrimary)
        if (right != null) AppText(right, AppTypography.Label, AppColors.TextTertiary)
    }
}

// ---------------------------------------------------------------- 场次行

/**
 * 场次行：左侧日期块 + 右侧信息。
 *
 * 每局比分**并排显示**（胜局用品牌蓝、负局用对方橙）——
 * 三局两胜的比赛，一眼看出"哪局赢了哪局输了"比只看 2:1 有用得多。
 * 体力用 ●●●●○ 的点阵表示，比数字更省视觉带宽。
 *
 * 短按进详情，**长按在手指处弹出编辑菜单**（打开/编辑/删除）。
 * 手势用项目自定的 350ms 阈值而非系统值 —— 见 [detectTapAndLongPress]。
 */
@Composable
private fun SessionRow(
    item: SessionListItem,
    onClick: () -> Unit,
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    val date = Instant.ofEpochMilli(item.date).atZone(ZoneId.systemDefault()).toLocalDate()

    GlassPanel(
        key = "session.${item.id}",
        modifier = Modifier
            .fillMaxWidth()
            .longPressable(
                key = item.id,
                onTap = onClick,
                onLongPressAt = onLongPress,
            ),
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- 日期块：大数字日 + 月份缩写
            Column(
                modifier = Modifier
                    .width(46.dp)
                    .background(AppColors.MeDim, AppShapes.CardSmall)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText(
                    text = date.dayOfMonth.toString(),
                    style = AppTypography.Title.copy(fontSize = AppTypography.Title.fontSize * 1.35f),
                    color = AppColors.MeSoft,
                )
                AppText(
                    text = date.month.name.take(3).uppercase(),
                    style = AppTypography.Label,
                    color = AppColors.TextTertiary,
                )
            }

            // ---- 信息
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = item.venue.ifBlank { item.type.label },
                        style = AppTypography.CardTitle,
                        color = AppColors.TextPrimary,
                    )
                    ResultBadge(item)
                }

                if (item.gameScores.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.gameScores.split(',').forEach { score ->
                            val my = score.substringBefore('-').toIntOrNull() ?: 0
                            val opp = score.substringAfter('-').toIntOrNull() ?: 0
                            AppText(
                                text = score,
                                style = AppTypography.Body,
                                color = when {
                                    my > opp -> AppColors.MeSoft
                                    my < opp -> AppColors.TextTertiary
                                    else -> AppColors.TextSecondary
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.opponents.isNotBlank()) {
                        AppText("vs ${item.opponents}", AppTypography.Label, AppColors.TextSecondary)
                    }
                    if (item.durationMin > 0) {
                        AppText(Formatters.duration(item.durationMin), AppTypography.Label, AppColors.TextTertiary)
                    }
                    if (item.feeYuan > 0) {
                        AppText(Formatters.money(item.feeYuan), AppTypography.Label, AppColors.TextTertiary)
                    }
                    StaminaDots(item.stamina)
                }
            }
        }
    }
}

/** 结果徽章：胜（品牌蓝）/ 负（对方橙）/ 平（灰） */
@Composable
private fun ResultBadge(item: SessionListItem) {
    val decided = item.myGamesWon != item.oppGamesWon
    val (text, color) = when {
        !decided -> "平" to AppColors.TextTertiary
        item.myGamesWon > item.oppGamesWon -> "胜" to AppColors.MeSoft
        else -> "负" to AppColors.OpponentSoft
    }
    Box(
        Modifier
            .clip(AppShapes.Chip)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        AppText(text, AppTypography.Label, color)
    }
}

/** 体力点阵：1~5 → ●●●●○ */
@Composable
private fun StaminaDots(stamina: Int) {
    if (stamina <= 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .background(
                        color = if (i < stamina) AppColors.MeSoft else AppColors.TextTertiary.copy(alpha = 0.35f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/** 空态 */
@Composable
private fun EmptyTimeline() {
    GlassPanel(
        key = "timeline.empty",
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(18.dp),
    ) {
        Column {
            AppText("还没有记录", AppTypography.CardTitle, AppColors.TextPrimary)
            Spacer(Modifier.height(4.dp))
            AppText(
                text = "点上面的「开始计分」，球已经开始计了；场馆和对手赛后补录就行。",
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
            )
        }
    }
}
