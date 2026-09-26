package com.retropro.feature.review

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.model.Rally
import com.retropro.data.model.Scorer
import com.retropro.di.AppGraph
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppText
import com.retropro.uikit.RallyNotePanel
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.theme.AppColors
import androidx.compose.ui.draw.clip
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import kotlinx.coroutines.launch

/**
 * 逐球复盘 —— **RetroPro 存在的理由**（Retro = 复盘）。
 *
 * 这一屏回答一个问题：**这一局我到底是怎么输/赢的？**
 *
 * 页面按"从结论到证据"排：
 *  1. 顶部局比分
 *  2. **本局标签分布** —— 一眼看出丢分集中在哪（比如"出界 ×5"）
 *  3. **未复盘提示** —— 直接点名还有几球没想过
 *  4. 逐球列表 —— 每一球的得分方 / 标签 / 心得，点开可改
 *
 * ## 为什么"未复盘"要单独提示
 *
 * 复盘的最大敌人是遗忘。赛后 30 分钟内是记忆最完整的时候，
 * 但用户往往懒得逐球写。把"还有 7 球没想"显性化，比任何提醒推送都有效 ——
 * 它是一个具体的、可以立刻完成的小任务，而不是一个抽象的"要记得复盘"。
 */
@Composable
fun RallyReviewScreen(
    gameId: Long,
    onBack: () -> Unit,
) {
    val repo = AppGraph.diary
    val scope = rememberCoroutineScope()

    val rallies by remember(gameId) { repo.observeRallies(gameId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tagCounts by remember(gameId) { repo.observeTagCounts(gameId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var editing by remember { mutableStateOf<Rally?>(null) }

    val my = rallies.count { it.scorer == Scorer.ME }
    val opp = rallies.count { it.scorer == Scorer.OPPONENT }
    val unreviewed = rallies.count { !it.isReviewed }

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
            item {
                Column {
                    AppText("逐球复盘", AppTypography.Display, AppColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    // 原型：第 N 局 · 我 X - Y 对方
                    AppText(
                        text = "第 ${gameId} 局 · 我 $my - $opp 对方",
                        style = AppTypography.Caption,
                        color = AppColors.TextTertiary,
                    )
                }
            }

            // ---- 标签分布：先给结论
            if (tagCounts.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "这一局的标签分布",
                        subtitle = "按出现次数排序",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tagCounts.forEach { (tag, count) ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    AppText(tag, AppTypography.Body, AppColors.TextPrimary)
                                    AppText("×$count", AppTypography.Body, AppColors.OpponentSoft)
                                }
                            }
                        }
                    }
                }
            }

            // ---- 未复盘提示：把"该做的事"变成具体的小任务
            if (rallies.isNotEmpty()) {
                item {
                    SectionCard(
                        title = "本局关键球",
                        subtitle = if (unreviewed > 0) {
                            "还有 $unreviewed 球没记心得 · 赛后 30 分钟内记忆最完整"
                        } else {
                            "已复盘"
                        },
                    ) {
                        AppText(
                            text = "已复盘 ${rallies.size - unreviewed} / ${rallies.size} 球",
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
            }

            if (rallies.isEmpty()) {
                item {
                    SectionCard(title = "这一局还没有逐球记录") {
                        AppText(
                            text = "在计分板里左右点击即可记分；长按会顺手弹出标签面板。",
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
            }

            // ---- 逐球列表
            items(rallies, key = { it.id }) { rally ->
                RallyRow(rally = rally, onClick = { editing = rally })
            }

            item {
                SecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        }

        val rally = editing
        if (rally != null) {
            RallyNotePanel(
                rally = rally,
                onDismiss = { editing = null },
                onSave = { tag, note ->
                    scope.launch {
                        repo.updateRally(rally.copy(reasonTag = tag, note = note))
                        editing = null
                    }
                },
            )
        }
    }
}

/**
 * 一条逐球记录。
 *
 * 未复盘的球用**左侧蓝色竖条 + 高亮序号**标出来 —— 这是整页的视觉重点：
 * 用户扫一眼就知道还有哪几球没写。
 */
@Composable
private fun RallyRow(rally: Rally, onClick: () -> Unit) {
    val mine = rally.scorer == Scorer.ME
    val reviewed = rally.isReviewed

    SectionCard(
        title = "%02d".format(rally.seq),
        subtitle = if (mine) "我方得分" else "对方得分",
        modifier = Modifier
            .clip(AppShapes.Card)
            .clickable(onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .background(
                            color = if (mine) AppColors.Me else AppColors.Opponent,
                            shape = AppShapes.Chip,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    AppText(
                        text = if (mine) "+1" else "失分",
                        style = AppTypography.Label,
                        color = AppColors.OnAccent,
                    )
                }
                if (!rally.reasonTag.isNullOrBlank()) {
                    SelectableChip(text = rally.reasonTag, selected = true, onClick = onClick)
                }
                if (!reviewed) {
                    AppText("未复盘", AppTypography.Label, AppColors.MeSoft)
                }
            }
            AppText(
                text = rally.note?.takeIf { it.isNotBlank() } ?: "（本球未记录心得，仅存比分）",
                style = AppTypography.Body,
                color = if (reviewed) AppColors.TextPrimary else AppColors.TextTertiary,
            )
        }
    }
}
