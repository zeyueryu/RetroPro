package com.retropro.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppText
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppTypography

/**
 * 「我的」—— 一级界面只有 **List Item 长条入口**，具体设置全部下沉到二级页。
 *
 * ## 为什么一级不放内容
 *
 * 提醒阈值、主题、背景这些设置的使用频率远低于"看一眼在哪"。
 * 一级页保持清爽，设置项在二级页里有完整的展开空间（[SettingsScreens.kt] 的四个 Screen）。
 * 导航栏规则配套：点长条进入二级页时底部导航栏自动隐藏（AppShell 按 route 控制）。
 *
 * ## 入口的归属
 *
 * 「材质验证」不是设置项而是验证工具，已下沉为 **外观（二级）→ 材质验证（三级）**，
 * 由 [AppearanceScreen] 的 `onOpenMaterialLab` 进入 —— 这里不再放它的一级入口。
 *
 * `ListItem` 本身已搬到 [SettingsScreens.kt]（`internal`），供外观页复用作三级入口。
 */
@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onOpenAppearance: () -> Unit = {},
    onOpenQuote: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            // 「我的」线严格 MIUIX：页面铺实底，阻断背后光晕/条纹 —— 没有折射对象，也就无从出现液态效果
            .background(AppColors.Background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            AppText("我的", AppTypography.Display, AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "设置 · 提醒 · 备份",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }

        // ---- 功能入口（List Item 长条）
        ListItem(
            title = "外观",
            detail = "深色模式 · AMOLED · 录音按钮 · 背景自定义 · 材质验证",
            onClick = onOpenAppearance,
        )
        ListItem(
            title = "每日格言",
            detail = "记录页左上角 · 预设每日轮换 · 也可固定一句自己的",
            onClick = onOpenQuote,
        )
        ListItem(
            title = "提醒",
            detail = reminders.firstOrNull { it.due }?.let { "${it.label} · 该行动了" }
                ?: "换线 / 休息提醒的开关与阈值",
            onClick = onOpenReminders,
        )
        ListItem(
            title = "备份与恢复",
            detail = "导出 JSON / 从备份恢复",
            onClick = onOpenBackup,
        )

        Spacer(Modifier.height(8.dp))

        // ---- 版本号（连点 5 次开启调试面板；release 构建无效）
        var taps by remember { mutableStateOf(0) }
        var lastTap by remember { mutableStateOf(0L) }
        AppText(
            text = com.retropro.BuildConfig.VERSION_NAME + " (" + com.retropro.BuildConfig.VERSION_CODE + ")",
            style = AppTypography.Caption,
            color = AppColors.TextTertiary,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .clickable {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 800) taps + 1 else 1
                    lastTap = now
                    if (taps >= 5) {
                        com.retropro.uikit.DebugUiState.enabled =
                            !com.retropro.uikit.DebugUiState.enabled
                        taps = 0
                    }
                },
        )
    }
}
