package com.retropro.feature.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.db.MatchWithOpponent
import com.retropro.data.db.SessionGameItem
import com.retropro.data.model.MatchMode
import com.retropro.data.model.MatchResult
import com.retropro.data.model.Session
import com.retropro.data.model.SessionType
import com.retropro.di.AppGraph
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppTextField
import com.retropro.uikit.AppText
import com.retropro.uikit.ChipSelector
import com.retropro.uikit.NumberStepper
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.Formatters
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 场次详情 —— 从记录列表点进来的那一页。
 *
 * 层级：场次信息 → 各场对阵（含该对阵下的每一局）→ 点某一局进逐球复盘。
 *
 * ## 为什么"局"要铺开显示在对阵下面，而不是藏进二级页
 *
 * 用户点进场次详情的目的是**回顾这一场打得怎么样**。
 * 如果只有"3:1"这样一个总数，还得再点一次才知道每局比分、才知道哪局没复盘。
 * 把局平铺出来，页面一次就能读完 —— 这也是逐球复盘的入口，
 * 每局后面直接标出"复盘 3/18 球"，一眼看到哪局欠账。
 *
 * ## 为什么这一页没有 ViewModel
 *
 * 页面上所有可变状态（比分、局数、对手）**都在 Room 里**，
 * 而 Room 的 Flow 本身就是响应式的。加一层 ViewModel 只是把
 * `repo.observeXxx()` 转手转发一次，不解决任何问题。
 */
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onOpenScoreboard: (matchId: Long) -> Unit,
    onOpenReview: (gameId: Long) -> Unit,
) {
    val repo = AppGraph.diary
    val scope = rememberCoroutineScope()

    val session by remember(sessionId) { repo.observeSession(sessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val matches by remember(sessionId) { repo.observeMatchesWithOpponent(sessionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val games by remember(sessionId) { repo.observeSessionGames(sessionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val gamesByMatch = remember(games) { games.groupBy { it.matchId } }

    Column(
        modifier = Modifier
            .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            AppText("场次详情", AppTypography.Display, AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            val s = session
            AppText(
                text = if (s == null) "" else buildString {
                    append(Formatters.fullDate(s.date))
                    if (s.venue.isNotBlank()) append(" · ").append(s.venue)
                },
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }

        session?.let {
            SessionInfoCard(
                session = it,
                onSave = { updated -> scope.launch { repo.updateSession(updated) } },
            )
        }

        matches.forEach { match ->
            MatchCard(
                match = match,
                games = gamesByMatch[match.id].orEmpty(),
                onOpenScoreboard = { onOpenScoreboard(match.id) },
                onOpenReview = onOpenReview,
                onEdit = { name, mode ->
                    scope.launch { repo.updateMatchInfo(match.id, name, mode) }
                },
            )
        }

        AddMatchCard { opponent, mode ->
            scope.launch { repo.addMatch(sessionId, opponent, mode) }
        }

        SecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- 场次信息

@Composable
private fun SessionInfoCard(session: Session, onSave: (Session) -> Unit) {
    var editing by remember { mutableStateOf(false) }

    SectionCard(
        title = session.type.label,
        subtitle = session.note?.takeIf { it.isNotBlank() } ?: "整体心得未填写",
    ) {
        if (editing) {
            EditSessionForm(
                session = session,
                onSave = {
                    editing = false
                    onSave(it)
                },
                onCancel = { editing = false },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("时长", Formatters.duration(session.durationMin))
                InfoLine("费用", Formatters.money(session.feeYuan))
                InfoLine("体力消耗", "${session.stamina} / 5")
                InfoLine("记录时间", Formatters.time(session.createdAt))
                SecondaryButton(
                    text = "编辑信息",
                    onClick = { editing = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 场次信息编辑表单。
 *
 * 日期用「今天 / 昨天 / 前天」快捷 chips 而不是完整日期选择器：
 * 改日期 90% 落在最近三天（快速开球当天、补录昨天前天），更早的场次日期本来就没记错。
 * 选中只换**日期**、保留原时刻 —— 打球的钟点不动。
 */
@Composable
private fun EditSessionForm(session: Session, onSave: (Session) -> Unit, onCancel: () -> Unit) {
    var venue by remember { mutableStateOf(session.venue) }
    var type by remember { mutableStateOf(session.type) }
    var durationMin by remember { mutableStateOf(session.durationMin.toDouble()) }
    var feeYuan by remember { mutableStateOf(session.feeYuan) }
    var stamina by remember { mutableStateOf(session.stamina.toDouble()) }
    var note by remember { mutableStateOf(session.note ?: "") }

    val baseDate = remember(session.id) {
        Instant.ofEpochMilli(session.date).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    // 0=今天 1=昨天 …；原日期更早（差 >2 天）时三个 chip 都不选中
    var dayOffset by remember(session.id) {
        mutableStateOf(ChronoUnit.DAYS.between(baseDate, LocalDate.now()).toInt())
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("今天" to 0, "昨天" to 1, "前天" to 2).forEach { (label, offset) ->
                SelectableChip(
                    text = label,
                    selected = dayOffset == offset,
                    onClick = { dayOffset = offset },
                )
            }
            AppText(
                text = "当前：" + Formatters.fullDate(session.date),
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
        AppTextField(value = venue, onValueChange = { venue = it }, label = "场馆")
        ChipSelector(
            options = SessionType.entries.toList(),
            selected = type,
            labelOf = { it.label },
            onSelect = { type = it },
        )
        NumberStepper(
            label = "时长",
            value = durationMin,
            onValueChange = { durationMin = it },
            range = 0.0..300.0,
            step = 15.0,
            unit = "分",
            format = { if (it <= 0.0) "—" else it.toInt().toString() },
        )
        NumberStepper(
            label = "费用",
            value = feeYuan,
            onValueChange = { feeYuan = it },
            range = 0.0..1000.0,
            step = 5.0,
            unit = "元",
            format = { if (it <= 0.0) "—" else it.toInt().toString() },
        )
        NumberStepper(
            label = "体力消耗",
            value = stamina,
            onValueChange = { stamina = it },
            range = 1.0..5.0,
            step = 1.0,
            format = { "${it.toInt()} / 5" },
        )
        AppTextField(value = note, onValueChange = { note = it }, label = "整体心得")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "保存",
                onClick = {
                    val origTime = Instant.ofEpochMilli(session.date)
                        .atZone(ZoneId.systemDefault()).toLocalTime()
                    val newDate = LocalDate.now().minusDays(dayOffset.toLong())
                    val newMillis = newDate.atTime(origTime).atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                    onSave(
                        session.copy(
                            date = newMillis,
                            venue = venue.trim(),
                            type = type,
                            durationMin = durationMin.toInt(),
                            feeYuan = feeYuan,
                            stamina = stamina.toInt(),
                            note = note.takeIf { it.isNotBlank() },
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppText(label, AppTypography.Body, AppColors.TextSecondary)
        AppText(value, AppTypography.Body, AppColors.TextPrimary)
    }
}

// ---------------------------------------------------------------- 对阵卡

@Composable
private fun MatchCard(
    match: MatchWithOpponent,
    games: List<SessionGameItem>,
    onOpenScoreboard: () -> Unit,
    onOpenReview: (gameId: Long) -> Unit,
    onEdit: (opponentName: String?, mode: MatchMode) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val decided = match.myGamesWon != match.oppGamesWon
    val scoreColor = when {
        !decided -> AppColors.TextTertiary
        match.myGamesWon > match.oppGamesWon -> AppColors.MeSoft
        else -> AppColors.OpponentSoft
    }
    val resultLabel = when {
        !decided -> "未分胜负"
        match.result == MatchResult.WIN -> "胜"
        match.result == MatchResult.LOSS -> "负"
        else -> "平"
    }

    SectionCard(
        title = match.opponentName?.takeIf { it.isNotBlank() } ?: "未记对手",
        subtitle = match.mode.label,
    ) {
        if (editing) {
            EditMatchForm(
                match = match,
                onSave = { name, mode ->
                    editing = false
                    onEdit(name, mode)
                },
                onCancel = { editing = false },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = "${match.myGamesWon} : ${match.oppGamesWon}",
                        style = AppTypography.Title,
                        color = scoreColor,
                    )
                    AppText(resultLabel, AppTypography.CardTitle, scoreColor)
                }

                games.forEach { game ->
                    GameRow(game = game, onClick = { onOpenReview(game.gameId) })
                }

                SecondaryButton(
                    text = "编辑对手 / 赛制",
                    onClick = { editing = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(text = "继续计分", onClick = onOpenScoreboard)
            }
        }
    }
}

/** 对手 / 赛制编辑表单。对手留空 = 「未记对手」（仓储层置 null） */
@Composable
private fun EditMatchForm(
    match: MatchWithOpponent,
    onSave: (opponentName: String?, mode: MatchMode) -> Unit,
    onCancel: () -> Unit,
) {
    var opponent by remember { mutableStateOf(match.opponentName ?: "") }
    var mode by remember { mutableStateOf(match.mode) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTextField(value = opponent, onValueChange = { opponent = it }, label = "对手（可留空）")
        ChipSelector(
            options = MatchMode.entries.toList(),
            selected = mode,
            labelOf = { it.label },
            onSelect = { mode = it },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "保存",
                onClick = { onSave(opponent.takeIf { it.isNotBlank() }, mode) },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 单局行。
 *
 * 右侧"复盘 x/y 球"是本 App 最有信息量的一个小指标：
 * `x == y` 时用品牌蓝（做完的事），否则用次要灰（还欠着）。
 * 不做"打卡式"的红色警告 —— 复盘是自我要求，不是任务系统。
 */
@Composable
private fun GameRow(game: SessionGameItem, onClick: () -> Unit) {
    val won = game.myScore > game.oppScore
    val decided = game.myScore != game.oppScore
    val allReviewed = game.rallyCount > 0 && game.reviewedCount == game.rallyCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.CardSmall)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                text = "第 ${game.gameIndex} 局",
                style = AppTypography.Body,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.padding(horizontal = 5.dp))
            AppText(
                text = "${game.myScore} : ${game.oppScore}",
                style = AppTypography.CardTitle,
                color = when {
                    !decided -> AppColors.TextTertiary
                    won -> AppColors.MeSoft
                    else -> AppColors.OpponentSoft
                },
            )
        }

        if (game.rallyCount > 0) {
            AppText(
                text = "复盘 ${game.reviewedCount}/${game.rallyCount} 球",
                style = AppTypography.Label,
                color = if (allReviewed) AppColors.MeSoft else AppColors.TextTertiary,
            )
        } else {
            AppText("未开打", AppTypography.Label, AppColors.TextTertiary)
        }
    }
}

// ---------------------------------------------------------------- 加一场

@Composable
private fun AddMatchCard(onAdd: (opponent: String?, mode: MatchMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var opponent by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MatchMode.SINGLES) }

    SectionCard(
        title = "再加一场对阵",
        subtitle = "同一场球可以和不同的人各打几局",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expanded) {
                AppTextField(
                    value = opponent,
                    onValueChange = { opponent = it },
                    label = "对手（可留空）",
                )
                ChipSelector(
                    options = MatchMode.entries.toList(),
                    selected = mode,
                    labelOf = { it.label },
                    onSelect = { mode = it },
                )
                PrimaryButton(
                    text = "添加对阵",
                    onClick = {
                        onAdd(opponent.takeIf { it.isNotBlank() }, mode)
                        opponent = ""
                        expanded = false
                    },
                )
            } else {
                Box(Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = "添加",
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
