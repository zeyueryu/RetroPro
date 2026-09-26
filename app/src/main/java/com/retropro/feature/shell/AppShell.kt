package com.retropro.feature.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retropro.di.AppViewModelProvider
import com.retropro.feature.equipment.EquipmentScreen
import com.retropro.feature.record.CreateSessionScreen
import com.retropro.feature.record.RecordScreen
import com.retropro.feature.profile.ProfileScreen
import com.retropro.feature.profile.AppearanceScreen
import com.retropro.feature.profile.MaterialLabScreen
import com.retropro.feature.profile.QuoteSettingsScreen
import com.retropro.feature.profile.BackupScreen
import com.retropro.feature.profile.ProfileViewModel
import com.retropro.feature.profile.RemindersScreen
import com.retropro.feature.record.RecordViewModel
import com.retropro.feature.review.RallyReviewScreen
import com.retropro.feature.score.ScoreboardScreen
import com.retropro.feature.session.SessionDetailScreen
import com.retropro.feature.stats.OpponentStatsScreen
import com.retropro.feature.stats.StatsScreen
import com.retropro.feature.stats.StatsViewModel
import com.retropro.glass.GlassPanel
import com.retropro.glass.GlassScene
import com.retropro.glass.LocalLayerBackdrop
import com.retropro.uikit.AppBackground
import com.retropro.uikit.AppText
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.nav.LiquidNavBar
import com.retropro.uikit.nav.NavItem
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppTypography
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Tasks

/** 导航栏占位高度：64dp 条高 + 上下 14dp 外边距 + 系统手势条 */

/**
 * App 外壳：共享折射源的页面容器 + 液态玻璃底部导航栏。
 *
 * ## 路由
 *
 * 目前只有三个页面，用 [ShellRoute] 这个 sealed interface 手写状态机，
 * **不引入 Navigation Compose**。理由：这几级页面之间不需要深链接、
 * 不需要参数序列化、也不需要多栈回退；引入导航库带来的是依赖和心智负担，
 * 而收益要到页面数量上两位数才体现。
 *
 * 等页面数上来了（场次详情 / 计分板 / 逐球复盘 / 装备详情 …）再换成 Navigation，
 * 那时引入的成本是一样的。
 *
 * ## 结构要点
 *
 * ```
 * GlassScene(background = AppBackground)     ← 折射源（背景层）
 *   ├─ content  = 页面内容，视口在导航栏之上
 *   └─ overlay  = LiquidNavBar（浮动玻璃条）
 * ```
 *
 * `overlay` 与 `content` 都在折射源**之外**，玻璃面板绝不会落进自己被录制的图层里，
 * 从结构上排除了 record → read → re-record 的自引用崩溃路径。
 */
@Composable
fun AppShell() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<ShellRoute>(ShellRoute.Root) }

    val items = remember {
        listOf(
            NavItem(MiuixIcons.Regular.Notes, "记录"),
            NavItem(MiuixIcons.Regular.Tasks, "统计"),
            NavItem(MiuixIcons.Regular.Store, "装备"),
            NavItem(MiuixIcons.Regular.Contacts, "我的"),
        )
    }

    val recordVm: RecordViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val statsVm: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val profileVm: ProfileViewModel = viewModel(factory = AppViewModelProvider.Factory)

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 计分板是独立的横屏全屏页：不套导航栏与背景层，
    // 由它自己处理返回键（防误退丢分，需二次确认）
    val fullRoute = route
    if (fullRoute is ShellRoute.Scoreboard) {
        ScoreboardScreen(
            matchId = fullRoute.matchId,
            onExit = { route = ShellRoute.SessionDetail(fullRoute.sessionId) },
        )
        return
    }

    // 其余子页面：返回键按层级回退，而不是直接退回一级列表。
    // ⚠️ 三级页必须回它的父页（材质验证 → 外观），不能一律回 Root，
    //    否则系统返回键会"跳过"外观页，与页面上的「返回」按钮行为不一致。
    BackHandler(enabled = route != ShellRoute.Root) {
        route = when (route) {
            is ShellRoute.MaterialLab -> ShellRoute.SettingsAppearance
            else -> ShellRoute.Root
        }
    }

    // 导航栏只在一级界面（Tab 根页面）显示：进入任何二级页面自动隐藏。
    // 计分板在上面已提前 return（横屏全屏，无导航栏）。
    // NavBarVisible 同步驱动各屏的底部净空（navBarClearance），避免隐藏后留一条空位。
    val atRoot = route is ShellRoute.Root
    com.retropro.uikit.NavBarVisible = atRoot

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    GlassScene(
        modifier = Modifier.fillMaxSize(),
        background = { AppBackground() },
        overlay = {
            if (atRoot) {
            LiquidNavBar(
                items = items,
                selectedIndex = tab,
                onSelect = {
                    tab = it
                    // 切 Tab 一律回到该 Tab 的根页面，避免"在 A 的子页切到 B 再切回来"的错位
                    route = ShellRoute.Root
                },
                // 页面级共享折射源；未启用玻璃时为 null，导航栏自动退化为纯色
                backdrop = LocalLayerBackdrop.current,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 14.dp,
                        bottom = navBarInset + 14.dp,
                    )
                    // 自适应：大屏（平板/折叠展开）导航栏不无限拉伸，限宽居中
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
            )
            }
        },
    ) {
        // 滚动区域不做任何避让：内容直通屏幕底，从玻璃导航栏下穿过；
        // 首尾可读区由各屏的滚动内容 padding 自行负责（uikit/NavMetrics）
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            transitionSpec = {
                val entering = targetState !is ShellRoute.Root && initialState is ShellRoute.Root
                val returning = targetState is ShellRoute.Root && initialState !is ShellRoute.Root
                when {
                    // 进二级：新页从右滑入 + 淡入，旧页淡出
                    entering ->
                        (androidx.compose.animation.slideInHorizontally { it / 5 } + androidx.compose.animation.fadeIn()) togetherWith
                            androidx.compose.animation.fadeOut()
                    // 返回一级：旧页向右滑出，新页淡入
                    returning ->
                        androidx.compose.animation.fadeIn() togetherWith
                            (androidx.compose.animation.slideOutHorizontally { it / 5 } + androidx.compose.animation.fadeOut())
                    // 其他（计分板等全屏跳转）：快速淡入淡出
                    else ->
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
                }
            },
            label = "route-transition",
        ) { currentRoute ->
        Box(Modifier.fillMaxSize()) {
            when (val current = currentRoute) {
                is ShellRoute.SessionDetail -> SessionDetailScreen(
                    sessionId = current.sessionId,
                    onBack = { route = ShellRoute.Root },
                    onOpenScoreboard = { matchId ->
                        route = ShellRoute.Scoreboard(current.sessionId, matchId)
                    },
                    onOpenReview = { gameId ->
                        route = ShellRoute.Review(current.sessionId, gameId)
                    },
                )

                is ShellRoute.Review -> RallyReviewScreen(
                    gameId = current.gameId,
                    onBack = { route = ShellRoute.SessionDetail(current.sessionId) },
                )

                is ShellRoute.OpponentStats -> OpponentStatsScreen(
                    vm = statsVm,
                    onBack = { route = ShellRoute.Root },
                )

                is ShellRoute.SettingsAppearance -> AppearanceScreen(
                    onBack = { route = ShellRoute.Root },
                    onOpenMaterialLab = { route = ShellRoute.MaterialLab },
                )

                // 三级页：返回回「外观」而不是直达「我的」（与上面的 BackHandler 一致）
                is ShellRoute.MaterialLab -> MaterialLabScreen(
                    onBack = { route = ShellRoute.SettingsAppearance },
                )

                is ShellRoute.SettingsQuote -> QuoteSettingsScreen(
                    onBack = { route = ShellRoute.Root },
                )

                is ShellRoute.SettingsReminders -> RemindersScreen(
                    vm = profileVm,
                    onBack = { route = ShellRoute.Root },
                )

                is ShellRoute.SettingsBackup -> BackupScreen(
                    vm = profileVm,
                    onBack = { route = ShellRoute.Root },
                )

                is ShellRoute.Scoreboard -> Unit // 已在上面全屏处理

                is ShellRoute.CreateSession -> CreateSessionScreen(
                    vm = recordVm,
                    onBack = { route = ShellRoute.Root },
                    onCreated = {
                        recordVm.refreshVenues()
                        route = ShellRoute.Root
                    },
                )

                // 「快速记录 / 语音一句话」：复用补录表单，进来就自动打开语音面板
                ShellRoute.VoiceLog -> CreateSessionScreen(
                    vm = recordVm,
                    autoOpenVoice = true,
                    onBack = { route = ShellRoute.Root },
                    onCreated = {
                        recordVm.refreshVenues()
                        route = ShellRoute.Root
                    },
                )

                ShellRoute.Root -> when (tab) {
                    0 -> RecordScreen(
                        vm = recordVm,
                        onOpenSession = { sessionId ->
                            route = ShellRoute.SessionDetail(sessionId)
                        },
                        onOpenCreateForm = { route = ShellRoute.CreateSession(openVoice = false) },
                        onStartedQuickMatch = { qs ->
                            // 快速开一场建完 场次+对阵+第1局 后，**直接进横屏计分板**。
                            // 球场上的动作链只有一步：按下 → 开始计分。
                            // 场馆/对手/费用赛后在场次详情里补录。
                            recordVm.refreshVenues()
                            route = ShellRoute.Scoreboard(qs.sessionId, qs.matchId)
                        },
                    )

                    1 -> StatsScreen(statsVm, onOpenOpponentStats = { route = ShellRoute.OpponentStats })

                    2 -> EquipmentScreen()

                    else -> ProfileScreen(
                        vm = profileVm,
                        onOpenAppearance = { route = ShellRoute.SettingsAppearance },
                        onOpenQuote = { route = ShellRoute.SettingsQuote },
                        onOpenReminders = { route = ShellRoute.SettingsReminders },
                        onOpenBackup = { route = ShellRoute.SettingsBackup },
                    )
                }
            }
        }
        } // AnimatedContent 闭合
    }

    // ---- 调试面板悬浮层（仅 debug 构建 + 连点版本号 5 次开启）
    com.retropro.feature.debug.DebugPanelHost(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = com.retropro.uikit.navBarClearance() + 12.dp),
    )
    } // 根 Box 闭合
}

/** 壳内路由。页面数量少时手写状态机比引导航库更直接 */
private sealed interface ShellRoute {
    data object Root : ShellRoute
    data class CreateSession(val openVoice: Boolean) : ShellRoute

    /** 补录表单 + 自动打开语音面板（快速记录 / 语音一句话） */
    data object VoiceLog : ShellRoute

    /** 场次详情。带上 sessionId 才能在返回时回到正确的那一场 */
    data class SessionDetail(val sessionId: Long) : ShellRoute

    /**
     * 全屏计分板。
     *
     * 带 sessionId 是为了退出时能回到对应的场次详情 ——
     * 计分板横屏全屏，不套 App 外壳（导航栏在比赛时是干扰）。
     */
    data class Scoreboard(val sessionId: Long, val matchId: Long) : ShellRoute

    /** 逐球复盘。带 sessionId 是为了返回时回到对应的场次详情 */
    data class Review(val sessionId: Long, val gameId: Long) : ShellRoute

    /** 对手局数统计二级界面（统计页卡片点进来） */
    data object OpponentStats : ShellRoute

    /** 我的 → 外观 */
    data object SettingsAppearance : ShellRoute

    /** 我的 → 每日格言 */
    data object SettingsQuote : ShellRoute

    /** 我的 → 提醒 */
    data object SettingsReminders : ShellRoute

    /** 我的 → 备份与恢复 */
    data object SettingsBackup : ShellRoute

    /**
     * 我的 → 外观 → 材质验证（**三级**）。
     *
     * 一级入口已从「我的」列表移除，本路由只能从 [SettingsAppearance] 进入；
     * 返回也回 [SettingsAppearance]（见渲染分支与 BackHandler）。
     */
    data object MaterialLab : ShellRoute
}
