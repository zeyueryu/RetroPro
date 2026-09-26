package com.retropro.uikit.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * App 主题入口。
 *
 * 使用 MIUIX 的 [MiuixTheme] 作为设计语言基底（Xiaomi HyperOS 风格）。
 *
 * ## 模式跟随 [AppColors.isDark]
 *
 * 主题模式不是写死的：MIUIX 的组件配色（输入框、开关、等）由
 * [ThemeController] 决定，而项目自己的语义色由 [AppColors] 决定，
 * 两者必须同步，否则会出现"MIUIX 输入框是深色、卡片文字是深色"这种错配。
 *
 * 这里把 `controller` 的创建 key 绑到 [AppColors.isDark]，
 * 切换模式时重建 controller —— `remember` 的 key 变化会触发重建，
 * 两层配色因此永远一致。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val isDark = AppColors.isDark
    val controller = remember(isDark) {
        ThemeController(if (isDark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    MiuixTheme(controller = controller, content = content)
}
