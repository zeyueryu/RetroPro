package com.retropro.feature.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.retropro.uikit.AppText
import com.retropro.uikit.DebugUiState
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import top.yukonga.miuix.kmp.basic.Slider

/**
 * 调试面板（Debug Panel）—— 仅 debug 构建可见。
 *
 * ## 功能
 *  - 三个滑块实时调节全局 UI 参数：圆角（0–50dp）/ 卡片透明度（0–1）/ 折射强度（0–2）
 *  - 拖动即时生效（无刷新 Live Preview——参数是 mutableStateOf，玻璃卡组合自动重组）
 *  - 「重置为默认值」回到 26dp / 1.0 / 1.0
 *  - 「复制 JSON」「复制 CSS」把调好的数值直接写回正式代码
 *
 * ## 显示条件
 * `BuildConfig.DEBUG && DebugUiState.enabled`——后者由连点版本号 5 次触发（ProfileScreen）。
 * 正式发布（release）构建里 BuildConfig.DEBUG 恒为 false，面板**绝不会出现**。
 */
@Composable
fun DebugPanelHost(modifier: Modifier = Modifier) {
    if (!com.retropro.BuildConfig.DEBUG) return
    if (!DebugUiState.enabled) return

    var expanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 参数面板
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.Sheet)
                    .background(AppColors.SurfaceFallback.copy(alpha = 0.92f))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppText("调试面板 · 全局 UI", AppTypography.CardTitle, AppColors.TextPrimary)

                SliderRow(
                    label = "圆角",
                    value = DebugUiState.borderRadius,
                    valueText = "${DebugUiState.borderRadius.round1()}px",
                    range = 0f..50f,
                    onChange = { DebugUiState.borderRadius = it },
                )
                SliderRow(
                    label = "卡片透明度",
                    value = DebugUiState.cardOpacity,
                    valueText = DebugUiState.cardOpacity.round1(),
                    range = 0f..1f,
                    onChange = { DebugUiState.cardOpacity = it },
                )
                SliderRow(
                    label = "折射强度",
                    value = DebugUiState.refractionIntensity,
                    valueText = DebugUiState.refractionIntensity.round1(),
                    range = 0f..2f,
                    onChange = { DebugUiState.refractionIntensity = it },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DebugChip("重置为默认值", modifier = Modifier.weight(1f)) { DebugUiState.reset() }
                    DebugChip("复制 JSON", modifier = Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(DebugUiState.toJson()))
                        copied = "JSON"
                    }
                    DebugChip("复制 CSS", modifier = Modifier.weight(1f)) {
                        clipboard.setText(AnnotatedString(DebugUiState.toCssSnippet()))
                        copied = "CSS"
                    }
                }
                copied?.let {
                    AppText("已复制 $it 到剪贴板", AppTypography.Caption, AppColors.SuccessSoft)
                }
            }
        }

        // ---- 悬浮开关（FAB）
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(AppColors.Me)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            AppText(if (expanded) "收起" else "调试", AppTypography.Label, Color.White)
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppText(label, AppTypography.Body, AppColors.TextPrimary)
            AppText(valueText, AppTypography.Body, AppColors.MeSoft)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
        )
    }
}

@Composable
private fun DebugChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(AppShapes.Chip)
            .background(AppColors.MeDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text, AppTypography.Label, AppColors.MeSoft)
    }
}

private fun Float.round1(): String = "%.1f".format(this)
