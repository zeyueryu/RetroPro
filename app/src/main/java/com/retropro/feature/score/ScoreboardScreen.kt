package com.retropro.feature.score

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.model.Game
import com.retropro.data.model.Rally
import com.retropro.data.model.Scorer
import com.retropro.di.AppGraph
import com.retropro.uikit.AppText
import com.retropro.uikit.AppTextField
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.RallyNotePanel
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.detectTapAndLongPress
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.findActivity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 长按阈值。设计文档 §7.1 写 200ms，但实测在快速加分时容易误触，取 350ms */
private const val LONG_PRESS_MS = 350L

/**
 * 横屏计分板 —— 球场上用的那一屏。
 *
 * 设计要点全部来自设计方案 §7.1：
 *
 * | 要素 | 实现 |
 * |---|---|
 * | 强制横屏 | `requestedOrientation = LANDSCAPE`，退出时恢复原值 |
 * | 加分按钮 | 左右各占**整屏半区**，点哪儿都算，不用瞄准 |
 * | 比分 | 中央超大字号，`MiuixTheme` 字型放大到 110sp |
 * | 撤销 | 顶部右侧，撤回最近一球（含它的标签与心得） |
 * | 长按 | 350ms → 加分 + 弹出标签面板 |
 * | 保持常亮 | `FLAG_KEEP_SCREEN_ON`，比赛期间不熄屏 |
 * | 退出保护 | 返回键二次确认，防误退丢分 |
 *
 * ## 这一页刻意不用 `lens` 折射
 *
 * 计分板是**高频重绘**页面（每次加分都要重建离屏层与 RenderEffect 链）。
 * 按项目的液态玻璃安全规则，此类页面**禁用 `lens` 折射**，只保留轻量模糊。
 * 顶部条与标签面板的玻璃面板都传 `useLens = false`；
 * 左右两个加分区则干脆用纯色块 —— 它们的首要任务是无延迟响应与高可读性，
 * 材质感在这里是负资产。
 *
 * ## 状态来源
 *
 * 比分不放在本地 `var` 里，而是直接读 Room 的 Flow。
 * 每次加分走 `DiaryRepository.addRally()`，它在**一个事务**里
 * 插球 + 汇总比分 + 推导局结果 + 更新比赛计数，UI 随之自动刷新。
 * 这样即便横竖屏切换、进程被杀重建，看到的比分也一定与数据库一致。
 */
@Composable
fun ScoreboardScreen(
    matchId: Long,
    onExit: () -> Unit,
) {
    val repo = AppGraph.diary
    val scope = rememberCoroutineScope()

    val games by remember(matchId) { repo.observeGames(matchId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var currentGameId by remember(matchId) { mutableStateOf<Long?>(null) }

    // 进入时确定"当前该记哪一局"。
    //
    // ⚠️ 这里刻意**只依赖 matchId**，不去看 `games` 这个 Flow：
    //    Flow 首次发射前 `games` 是空列表，若据此判断"本场还没有局"，
    //    就会在已有第 1 局的情况下又建一局（实测进计分板直接冒出「第 2 局」）。
    //    改成向数据库查一次"局号最大的那一局"，结果确定且与发射时机无关。
    LaunchedEffect(matchId) {
        currentGameId = repo.latestGameId(matchId) ?: repo.addGame(matchId)
    }

    val gameId = currentGameId
    val currentGame: Game? = remember(games, gameId) { games.firstOrNull { it.id == gameId } }

    val rallies by remember(gameId) {
        if (gameId == null) flowOf(emptyList<Rally>())
        else repo.observeRallies(gameId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    // ---- 横屏锁定 + 常亮
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- 退出二次确认
    var showExitConfirm by remember { mutableStateOf(false) }
    BackHandler(enabled = true) { showExitConfirm = true }

    // ---- AMOLED 纯黑模式：进入计分板置 forceDark（= 意图 OR 覆写），退出自动清零。
    // 只写 forceDark 而不是 isDark：MainActivity 会按偏好随时重置意图层，
    // 直接写 isDark 会在横屏重建后的下一次重组里被吞掉（真机实测踩过）。
    val amoled = com.retropro.util.AppPrefs.Live.scoreboardAmoled
    androidx.compose.runtime.DisposableEffect(amoled) {
        com.retropro.uikit.theme.AppColors.forceDark = amoled
        onDispose { com.retropro.uikit.theme.AppColors.forceDark = false }
    }

    // ---- 长按后待补心得的这一球
    var pendingRallyId by remember { mutableStateOf<Long?>(null) }

    // ---- 整场语音分析面板（戴耳机录整场，VAD 切分 + 球序标注）
    // 状态在 ScoreboardScreen 托管：「收起」面板只隐藏 UI，录音/转写后台照常完成
    var showGlobalVoice by remember { mutableStateOf(false) }
    var gvRecorder by remember { mutableStateOf<com.retropro.feature.voice.GlobalRecorder?>(null) }
    var gvSeconds by remember { mutableStateOf(0) }
    var gvAnalyzing by remember { mutableStateOf(false) }
    var gvReport by remember { mutableStateOf<com.retropro.feature.voice.GlobalAnalyzer.Report?>(null) }

    fun gvStart() {
        val rec = com.retropro.feature.voice.GlobalRecorder(context)
        if (rec.start()) {
            gvRecorder = rec
            gvReport = null
            scope.launch {
                while (gvRecorder != null) {
                    delay(1_000)
                    gvSeconds = gvRecorder?.elapsedSeconds() ?: 0
                }
            }
        }
    }

    fun gvStop() {
        val rec = gvRecorder
        gvRecorder = null
        if (rec != null) {
            val file = rec.stop()
            gvAnalyzing = true
            scope.launch(Dispatchers.Default) {
                val r = if (com.retropro.feature.voice.LocalAsr.ensureReady(context) &&
                    com.retropro.feature.voice.GlobalAnalyzer.ensureVadReady(context)
                ) com.retropro.feature.voice.GlobalAnalyzer.analyze(file) else null
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    gvReport = r
                    gvAnalyzing = false
                }
            }
        }
    }

    // ---- 全局录音（随时记语音，停止后本地转写，可存为最新一球心得）
    var voiceRecorder by remember { mutableStateOf<com.retropro.feature.voice.GlobalRecorder?>(null) }
    var voiceSeconds by remember { mutableStateOf(0) }
    var voiceTranscribing by remember { mutableStateOf(false) }
    var voiceNoteDraft by remember { mutableStateOf<String?>(null) }  // 非空 = 显示转写结果卡

    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {

            // -------- 顶部条：局号 / 已结束局比分 / 撤销 / 结束本局
            ScoreboardTopBar(
                game = currentGame,
                games = games,
                onUndo = { scope.launch { gameId?.let { repo.undoLastRally(it) } } },
                onEndGame = {
                    scope.launch {
                        currentGameId = repo.addGame(matchId)
                    }
                },
                onQuit = { showExitConfirm = true },
                onGlobalVoice = { showGlobalVoice = !showGlobalVoice },
                globalVoiceLabel = when {
                    gvRecorder != null -> "语音 " + gvSeconds + "s"
                    gvAnalyzing -> "转写中…"
                    gvReport != null -> "看结果"
                    else -> "整场语音"
                },
            )

            // -------- 左右半屏加分
            Row(Modifier.fillMaxSize()) {
                ScoreHalf(
                    label = "我",
                    score = currentGame?.myScore ?: 0,
                    accent = AppColors.Me,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = {
                        scope.launch {
                            gameId?.let { repo.addRally(it, Scorer.ME) }
                        }
                    },
                    onLongPress = {
                        scope.launch {
                            val id = gameId ?: return@launch
                            val rallyId = repo.addRally(id, Scorer.ME)
                            pendingRallyId = rallyId
                        }
                    },
                )
                ScoreHalf(
                    label = "对方",
                    score = currentGame?.oppScore ?: 0,
                    accent = AppColors.Opponent,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onTap = {
                        scope.launch {
                            gameId?.let { repo.addRally(it, Scorer.OPPONENT) }
                        }
                    },
                    onLongPress = {
                        scope.launch {
                            val id = gameId ?: return@launch
                            val rallyId = repo.addRally(id, Scorer.OPPONENT)
                            pendingRallyId = rallyId
                        }
                    },
                )
            }
        }

        // -------- 最近几球的流水（左下角，不挡视线）
        RecentRalliesStrip(
            rallies = rallies,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 12.dp),
        )

        // -------- 长按后的标签 / 心得面板
        val rallyId = pendingRallyId
        if (rallyId != null) {
            val rally = rallies.firstOrNull { it.id == rallyId }
            RallyNotePanel(
                rally = rally,
                onDismiss = { pendingRallyId = null },
                onSave = { tag, note ->
                    scope.launch {
                        rally?.let { repo.updateRally(it.copy(reasonTag = tag, note = note)) }
                        pendingRallyId = null
                    }
                },
                // 计分板是高频重绘页：按液态玻璃安全规则禁用 lens 折射
                useLens = false,
            )
        }

            // -------- 全局录音：随时记语音，中缝常驻小圆钮
            val voiceRec = voiceRecorder
            Box(Modifier.align(Alignment.Center)) {
                GlobalRecordButton(
                    recording = voiceRec != null,
                    seconds = voiceSeconds,
                    onClick = {
                        if (voiceRec != null) {
                            // 停止 → 落盘 → 本地转写
                            val file = voiceRec.stop()
                            voiceRecorder = null
                            voiceTranscribing = true
                            scope.launch(Dispatchers.Default) {
                                val transcript = if (LocalAsrReady(context)) {
                                    com.retropro.feature.voice.LocalAsr.decodeWavFile(file).orEmpty()
                                } else ""
                                VoiceNoteStore.save(context, file, transcript)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    voiceTranscribing = false
                                    voiceNoteDraft = transcript
                                }
                            }
                        } else {
                            val rec = com.retropro.feature.voice.GlobalRecorder(context)
                            if (rec.start()) {
                                voiceRecorder = rec
                                voiceSeconds = 0
                                scope.launch {
                                    while (voiceRecorder != null) {
                                        kotlinx.coroutines.delay(1_000)
                                        voiceSeconds = voiceRecorder?.elapsedSeconds() ?: 0
                                    }
                                }
                            }
                        }
                    },
                )
            }

            // -------- 转写结果卡：存为最新一球心得 / 仅存语音
            val transcript = voiceNoteDraft
            val transcriptText = transcript.orEmpty()
            if (transcript != null || voiceTranscribing) {
                com.retropro.uikit.SectionCard(
                    title = if (voiceTranscribing) "语音转写中…" else "语音已转写",
                    subtitle = if (voiceTranscribing) "" else "音频已保存；文本可存为最新一球的心得",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.6f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!voiceTranscribing) {
                            AppText(
                                text = transcriptText.ifBlank { "（没识别出内容，音频已保存）" },
                                AppTypography.Body,
                                AppColors.TextPrimary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TopBarChip(
                                    "存为第 ${rallies.size + 1} 球心得",
                                    onClick = {
                                        val rally = rallies.lastOrNull()
                                        scope.launch {
                                            rally?.let {
                                                repo.updateRally(
                                                    it.copy(
                                                        note = if (transcriptText.isBlank()) "（语音笔记，未转写出内容）" else transcriptText,
                                                    ),
                                                )
                                            }
                                            voiceNoteDraft = null
                                        }
                                    },
                                )
                                TopBarChip("仅存语音", onClick = { voiceNoteDraft = null })
                            }
                        }
                    }
                }
            }

            if (showGlobalVoice) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(AppColors.TextPrimary.copy(alpha = 0.35f))
                        .pointerInput(Unit) {},
                )
                com.retropro.feature.voice.GlobalVoicePanel(
                    recording = gvRecorder != null,
                    seconds = gvSeconds,
                    analyzing = gvAnalyzing,
                    report = gvReport,
                    onStart = { gvStart() },
                    onStop = { gvStop() },
                    onDismiss = { showGlobalVoice = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }

        if (showExitConfirm) {
            ExitConfirmOverlay(
                onStay = { showExitConfirm = false },
                onLeave = {
                    showExitConfirm = false
                    onExit()
                },
            )
        }
    }
}

// ---------------------------------------------------------------- 顶部条

@Composable
private fun ScoreboardTopBar(
    game: Game?,
    games: List<Game>,
    onUndo: () -> Unit,
    onEndGame: () -> Unit,
    onQuit: () -> Unit,
    onGlobalVoice: () -> Unit = {},
    globalVoiceLabel: String = "整场语音",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            AppText(
                text = "第 ${game?.gameIndex ?: 1} 局",
                style = AppTypography.CardTitle,
                color = AppColors.TextPrimary,
            )
            val finished = games.filter { it.id != game?.id && (it.myScore > 0 || it.oppScore > 0) }
            if (finished.isNotEmpty()) {
                AppText(
                    text = finished.joinToString("　") { "${it.myScore}-${it.oppScore}" },
                    style = AppTypography.Caption,
                    color = AppColors.TextSecondary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBarChip("撤销", onUndo)
            TopBarChip("结束本局", onEndGame)
            TopBarChip("退出", onQuit)
            TopBarChip(globalVoiceLabel, onGlobalVoice)
        }
    }
}

@Composable
private fun TopBarChip(text: String, onClick: () -> Unit) {
    SelectableChip(text = text, selected = false, onClick = onClick)
}

// ---------------------------------------------------------------- 半屏加分区

@Composable
private fun ScoreHalf(
    label: String,
    score: Int,
    accent: Color,
    modifier: Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // 比分字号从 MIUIX 的 TextStyles 放大而来，保住 MIUIX 的字型
    val base = MiuixTheme.textStyles.title1
    val scoreStyle = base.copy(
        fontSize = 108.sp,
        lineHeight = 118.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )

    // 微动：按压缩放 + 轻触感（pointerInput 手势拿不到 interactionSource，
    // 用 onPressChanged 回调驱动同一套 spring 参数）
    var halfPressed by remember { mutableStateOf(false) }
    val halfScale by animateFloatAsState(
        targetValue = if (halfPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "halfScale",
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(halfPressed) {
        if (halfPressed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // ★★ 必须用 rememberUpdatedState 转发回调，不能把 onTap/onLongPress 直接塞进 pointerInput。
    //
    // 手势块只在**安装时**捕获一次闭包，而它的 key 是 `label`（「我」/「对方」）—— 永不变化。
    // 于是「结束本局」后 `gameId` 虽然换成了新一局，手势里用的仍是最初那个闭包、
    // 里面还攥着**旧局 id**：新一局点按不加分，球却记到了上一局。
    // 真机/模拟器复现：第 3 局被点到 4 分，第 4 局恒为 0:0（rallies.gameId 全部指向上局）。
    //
    // rememberUpdatedState 让手势块保持稳定（不重装、不丢进行中的长按），同时每次调用都取最新闭包。
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnLongPress by rememberUpdatedState(onLongPress)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = halfScale
                scaleY = halfScale
            }
            .padding(8.dp)
            .background(accent.copy(alpha = 0.10f), AppShapes.Card)
            .pointerInput(label) {
                detectTapAndLongPress(
                    longPressMillis = LONG_PRESS_MS,
                    onPressChanged = { halfPressed = it },
                    onTap = { latestOnTap() },
                    onLongPress = { latestOnLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(label, AppTypography.Caption, AppColors.TextSecondary)
            Spacer(Modifier.height(4.dp))
            AppText(
                text = score.toString(),
                style = scoreStyle,
                color = accent,
            )
        }
    }
}

// ---------------------------------------------------------------- 最近几球

@Composable
private fun RecentRalliesStrip(rallies: List<Rally>, modifier: Modifier = Modifier) {
    if (rallies.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText("最近", AppTypography.Label, AppColors.TextTertiary)
        rallies.takeLast(10).forEach { rally ->
            val mine = rally.scorer == Scorer.ME
            val reviewed = rally.isReviewed
            Box(
                Modifier
                    .background(
                        color = if (mine) AppColors.Me else AppColors.Opponent,
                        shape = AppShapes.IndexBadge,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                AppText(
                    text = if (reviewed) "${rally.seq}★" else rally.seq.toString(),
                    style = AppTypography.Label,
                    color = Color.White,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 标签 / 心得面板

// ---------------------------------------------------------------- 退出确认

@Composable
private fun BoxScope.ExitConfirmOverlay(onStay: () -> Unit, onLeave: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.TextPrimary.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null, onClick = onStay),
    )
    Box(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.62f),
    ) {
        SectionCard(
            title = "退出计分？",
            subtitle = "比分已经实时存库，退出不会丢分。",
            useLens = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(text = "继续比赛", onClick = onStay)
                SecondaryButton(
                    text = "退出计分板",
                    onClick = onLeave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 计分板转写用的引擎就绪检查（失败原因在 logcat：LocalAsr.initError） */
private fun LocalAsrReady(context: android.content.Context): Boolean =
    com.retropro.feature.voice.LocalAsr.ensureReady(context)
