package com.retropro.feature.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.db.OpponentGamesStat
import com.retropro.uikit.AppText
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography

/**
 * 对手局数统计 · 二级界面：全部对手的局数排行。
 *
 * 与统计页 Top 3 卡同源（[OpponentGamesStat]，按 games 真实比分聚合），
 * 这里展示全部对手，按总局数倒序。
 */
@Composable
fun OpponentStatsScreen(
    vm: StatsViewModel,
    onBack: () -> Unit,
) {
    val stats by vm.opponentStats.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            AppText("对手局数统计", AppTypography.Display, AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "全部对手 · 按交手总局数排序（局按真实比分计）",
                AppTypography.Caption,
                AppColors.TextTertiary,
            )
        }

        if (stats.isEmpty()) {
            SectionCard(title = "还没有对手数据", subtitle = "打完比赛后会按对手汇总") {
                AppText(
                    "每一局的比分都会计入对手的交手局数。",
                    AppTypography.Caption,
                    AppColors.TextSecondary,
                )
            }
        } else {
            stats.forEachIndexed { index, s ->
                SectionCard(
                    title = s.name?.ifBlank { "未知对手" } ?: "未知对手",
                    subtitle = "共 " + s.total + " 局",
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 名次徽章
                        Box(
                            Modifier
                                .background(rankColor(index), AppShapes.Chip)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            AppText(
                                "No." + (index + 1),
                                AppTypography.CardTitle,
                                AppColors.TextPrimary,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            AppText(
                                s.myWins.toString() + " 胜 · " + s.oppWins + " 负",
                                AppTypography.Body,
                                AppColors.TextPrimary,
                            )
                            AppText(
                                if (s.total > 0) "局胜率 " + (s.myWins * 100 / s.total) + "%" else "—",
                                AppTypography.Caption,
                                AppColors.TextTertiary,
                            )
                        }
                    }
                }
            }
        }

        SecondaryButton(text = "返回统计", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

private fun rankColor(index: Int) = when (index) {
    0 -> com.retropro.uikit.theme.AppColors.MeDim
    1 -> com.retropro.uikit.theme.AppColors.MeDim.copy(alpha = 0.6f)
    2 -> com.retropro.uikit.theme.AppColors.MeDim.copy(alpha = 0.35f)
    else -> com.retropro.uikit.theme.AppColors.NeutralFill
}
