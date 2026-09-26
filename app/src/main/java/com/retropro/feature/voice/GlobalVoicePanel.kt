package com.retropro.feature.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retropro.uikit.AppText
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography

private fun formatSec(v: Double): String = "%d:%02d".format(v.toInt() / 60, v.toInt() % 60)

/**
 * 整场语音分析面板 —— **纯展示组件**。
 *
 * ## 状态托管
 *
 * 录音/分析状态（recorder/seconds/analyzing/report）由宿主（ScoreboardScreen）持有：
 * 「收起」面板只是隐藏 UI，录音与转写在后台照常进行，完成后从顶条 chip 恢复查看。
 * 状态不放在本组件的 remember 里，收起再展开不丢任何进度。
 *
 * ## 动作
 *
 *  - [onStart] / [onStop]：录音开始/停止（宿主内实现，含权限）
 *  - [onDismiss]：收起面板（不是取消——后台任务继续）
 */
@Composable
fun GlobalVoicePanel(
    recording: Boolean,
    seconds: Int,
    analyzing: Boolean,
    report: GlobalAnalyzer.Report?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    SectionCard(
        title = "整场语音分析",
        subtitle = "戴着耳机录一整场；收起面板录音照常，停止后自动切分静音 / 叹气 / 语气词，并标注球序",
        modifier = modifier,
    ) {
        Column(
            Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null && !recording && !analyzing) {
                AppText(
                    "点录音开始，比赛全程不用管它；录完点停止（建议单场 ≤ 2 小时）",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                val btnColor = com.retropro.util.AppPrefs.recordButtonColor(context)
                Box(
                    Modifier
                        .size(64.dp)
                        .background(
                            color = if (recording) AppColors.Opponent else btnColor,
                            shape = CircleShape,
                        )
                        .clip(CircleShape)
                        .clickable(enabled = !analyzing) { if (recording) onStop() else onStart() },
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = when {
                            recording -> "${seconds}s"
                            analyzing -> "…"
                            else -> "录音"
                        },
                        AppTypography.Body,
                        Color.White,
                    )
                }
            }

            if (analyzing) {
                AppText(
                    "分析中：VAD 切分 + 逐段识别…",
                    AppTypography.Caption,
                    AppColors.TextSecondary,
                )
            }

            report?.let { r ->
                AppText(
                    text = "时长 ${formatSec(r.durationSec)} · 语音 ${formatSec(r.speechSec)} · " +
                        "静音 ${formatSec(r.silenceSec)} · 叹气 ${r.sighCount} · 语气词 ${r.fillerCount}",
                    AppTypography.Caption,
                    AppColors.TextSecondary,
                )
                r.items.forEach { item ->
                    Column(
                        Modifier
                            .background(AppColors.NeutralFill, AppShapes.Chip)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppText(
                                "${formatSec(item.startSec)}–${formatSec(item.endSec)}",
                                AppTypography.Label,
                                AppColors.TextTertiary,
                            )
                            AppText(
                                when (item.type) {
                                    GlobalAnalyzer.SegType.SPEECH -> "语音"
                                    GlobalAnalyzer.SegType.FILLER -> "无用词"
                                    GlobalAnalyzer.SegType.SIGH -> "叹气"
                                    GlobalAnalyzer.SegType.OTHER_EVENT -> "事件"
                                    GlobalAnalyzer.SegType.SILENCE -> "静音"
                                },
                                AppTypography.Label,
                                when (item.type) {
                                    GlobalAnalyzer.SegType.SPEECH -> AppColors.MeSoft
                                    GlobalAnalyzer.SegType.FILLER, GlobalAnalyzer.SegType.SIGH -> AppColors.OpponentSoft
                                    else -> AppColors.TextTertiary
                                },
                            )
                            AppText("第 ${item.rallyNo} 球", AppTypography.Label, AppColors.TextTertiary)
                        }
                        if (item.text.isNotBlank()) {
                            AppText(item.text, AppTypography.Body, AppColors.TextPrimary)
                        }
                        AppText(item.basis, AppTypography.Caption, AppColors.TextTertiary)
                    }
                }
            }

            // 「收起」= 隐藏面板，后台任务继续；恢复入口在顶条「整场语音」chip
            SecondaryButton(text = "收起", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
