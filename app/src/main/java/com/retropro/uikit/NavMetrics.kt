package com.retropro.uikit

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浮动玻璃导航栏的几何常量（与 AppShell 的 LiquidNavBar 保持一致）。
 *
 * 设计规则：滚动区域自身**不做任何系统栏避让** —— 内容从状态栏与导航栏下穿过；
 * 只有滚动内容的**首尾 padding**（在滚动内容内部，跟着滚）用它腾出可读区。
 */
const val NAV_BAR_HEIGHT_DP = 64
const val NAV_BAR_MARGIN_DP = 14

/** 条高 + 上下外边距 */
val NavBarTotal: Dp = NAV_BAR_HEIGHT_DP.dp + NAV_BAR_MARGIN_DP.dp * 2

/**
 * 导航栏当前是否可见。
 *
 * 由 AppShell 在路由切换时写入：一级页面（Tab 根页面）为 true，
 * 进入任何二级页面为 false —— 面板/二级页的内容底部净空随之收缩，
 * 否则导航栏隐藏了、内容底部还留着一条 78dp 的空位。
 */
var NavBarVisible: Boolean by mutableStateOf(true)

/** 滚动内容底部需要的净空 = 导航栏可见时总占位 + 手势条 inset；隐藏时只留手势条 */
@Composable
fun navBarClearance(): Dp =
    if (NavBarVisible) {
        NavBarTotal + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    }
