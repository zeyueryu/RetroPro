package com.retropro.feature.stats

import androidx.compose.ui.graphics.Color
import com.retropro.data.db.StatsOverview
import com.retropro.uikit.theme.AppColors
import com.retropro.util.Formatters

/**
 * 可供「自定义统计卡片」选择的指标。
 *
 * 每个指标的**口径**集中定义在这里（[valueOf]），与统计页正文、
 * `DashboardCard` 表共用同一份 —— 改口径只改一处。
 */
enum class MetricKey(
    val key: String,
    val label: String,
    val valueOf: (StatsOverview) -> String,
) {
    /** 胜利局数：`games.myScore > oppScore` 的计数（不是"赢了几场"） */
    WIN_GAMES("win_games", "胜利局数", { it.winGames.toString() }),

    /** 复盘局数：至少有 1 条已复盘逐球记录的局数 */
    REVIEWED_GAMES("reviewed_games", "复盘局数", { it.reviewedGames.toString() }),

    SESSION_COUNT("session_count", "场次", { "${it.sessionCount} 场" }),

    PLAYED_GAMES("played_games", "打出比分的局", { "${it.playedGames} 局" }),

    RALLY_COUNT("rally_count", "逐球记录", { "${it.rallyCount} 球" }),

    REVIEWED_RALLY_COUNT("reviewed_rally_count", "已写心得", { "${it.reviewedRallyCount} 球" }),

    TOTAL_FEE("total_fee", "累计花费", { Formatters.money(it.totalFee) }),

    LOSS_GAMES("loss_games", "失败局数", { it.lossGames.toString() }),
    ;

    /** 卡片数字的颜色。核心指标用品牌色，其余用主文字色 —— 避免满屏都是彩色 */
    val accent: Color
        get() = when (this) {
            WIN_GAMES -> AppColors.MeSoft
            REVIEWED_GAMES -> AppColors.SuccessSoft
            else -> AppColors.TextPrimary
        }

    companion object {
        fun of(key: String): MetricKey? = entries.firstOrNull { it.key == key }
    }
}
