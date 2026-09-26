package com.retropro.feature.m0

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.retropro.glass.GlassGuard
import com.retropro.glass.GlassLevel
import com.retropro.glass.GlassPanel
import com.retropro.glass.GlassRuntime
import com.retropro.glass.RefractionSpec
import com.retropro.uikit.AppText
import com.retropro.uikit.HorizontalChipRow
import com.retropro.feature.voice.LocalAsr
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.FrameStats
import com.retropro.util.rememberFrameStats
import kotlinx.coroutines.delay

/**
 * 材质验证的 8 个分区内容（**不含外壳**）。
 *
 * 由 [com.retropro.feature.profile.MaterialLabScreen] 套进 `SettingsScaffold` 使用 ——
 * 三级页的滚动、状态栏净空、标题与返回按钮都由骨架负责。
 *
 * ## 两条硬约束
 *
 * 1. **本函数不得自带 `verticalScroll` / `systemBarsPadding` / `fillMaxSize`** ——
 *    宿主骨架已经有一层 `verticalScroll`，同方向嵌套滚动会崩。
 * 2. **宿主页不得铺实底背景**（`SettingsScaffold(backgroundColor = null)`）——
 *    折射源（条纹/圆环/色球）与页面内容是 `GlassScene` 里的兄弟节点且在下方，
 *    实底会把它整块盖住，而 `GlassPanel` 读图层时不感知遮挡 → 折射验证失去判断依据。
 *
 * 间距由宿主的 `Arrangement.spacedBy(14.dp)` 提供，本函数不再加外层 Spacer。
 *
 * ## 验收标准（对应方案 §5 M0）
 *
 *  1. 折射演示区**肉眼可见地产生几何变形** —— 背景细条纹在玻璃边缘弯折、位移，
 *     而不是单纯变模糊。这是"玻璃真的在工作"的唯一证据。
 *     ⚠️ 若看到的是纯色板，先确认宿主页没铺实底、且外观页「背景」卡不是「纯色 / 简约」
 *     （那两种模式下 `AppBackground` 直接 return，根本不画条纹与色球）。
 *  2. 连续操作 10 分钟不崩溃 —— 重点观察熔断计数是否上涨。
 *  3. 帧率稳定、无持续掉帧 —— 观察 FPS 与最差单帧。
 */
@Composable
internal fun MaterialLabSections() {
    val stats = rememberFrameStats()

    // 每秒刷新一次熔断统计（与帧统计同频，避免引入额外重组）
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    // 折射验证卡放在最上方：正对背景的条纹带与色球，一进页面就能判断
    RefractionStage()
    RefractionControl()
    PerfCard(stats = stats, tick = tick)
    CapabilityCard()
    GuardCard(tick = tick)
    Controls()
    AsrTestCard()
}

// ---------------------------------------------------------------- 区块

/**
 * 折射验证舞台 —— M0 最重要的一块。
 *
 * 它是一块**空内容**的玻璃面板，正下方压着背景层里的细条纹、描边圆环与移动色球。
 * 判断标准：
 *  - 只看到"变模糊" / 什么都没有 → 折射没生效（检查 `LayerBackdrop` 是否被 `layerBackdrop` 标记）
 *  - 条纹在面板边缘出现弯折、位移，色球边缘被拉伸 → `lens()` 折射正常
 *  - 高强度档位下边缘出现轻微彩色镶边 → 色散（chromaticAberration）生效
 */
@Composable
private fun RefractionStage() {
    GlassPanel(
        key = "stage.refraction",
        refraction = currentRefraction,
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            AppText("折射验证", AppTypography.CardTitle, AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "面板下方有竖直细条纹、描边圆环和移动色球。" +
                    "若条纹在面板边缘发生弯折位移，说明折射已生效；只变模糊则未生效。",
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
            )
        }
    }
}

/** 折射强度选择。真机上调参用，避免"看不出来"时只能改代码重编。 */
@Composable
private fun RefractionControl() {
    GlassPanel(
        key = "card.refraction",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AppText("折射强度", AppTypography.CardTitle, AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefractionSpec.entries.forEach { spec ->
                    ActionChip(
                        text = spec.label,
                        primary = spec == currentRefraction,
                    ) { currentRefraction = spec }
                }
            }
            Spacer(Modifier.height(10.dp))
            AppText(
                text = "模糊 %.0fdp　·　折射高度 %.0fdp　·　位移 %.0fdp%s".format(
                    currentRefraction.blurDp,
                    currentRefraction.refractionHeightDp,
                    currentRefraction.refractionAmountDp,
                    if (currentRefraction.chromaticAberration) "　·　色散开" else "",
                ),
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/**
 * 全局折射强度状态。
 *
 * 用 `mutableStateOf` 而非 `remember` 局部状态：`drawBackdrop` 内部用
 * `observeReads` 观察 `effects` lambda 读到的状态，值变化时会自动重算 RenderEffect，
 * 这正是官方 playground 的做法。
 */
private var currentRefraction by mutableStateOf(RefractionSpec.Standard)

@Composable
private fun CapabilityCard() {
    val detection = GlassRuntime.detection
    val activeLevel = GlassRuntime.activeLevel
    GlassPanel(
        key = "card.capability",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("当前档位", AppTypography.Label, AppColors.TextTertiary)
                Box(
                    Modifier
                        .clip(AppShapes.Chip)
                        .background(AppColors.MeDim)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    AppText(activeLevel.label, AppTypography.Label, AppColors.MeSoft)
                }
            }
            Spacer(Modifier.height(10.dp))
            AppText(
                text = detection?.reason ?: "未初始化",
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            AppText(
                text = "RuntimeShader：" +
                    if (detection?.runtimeShaderSupported == true) "可用" else "不可用" +
                    "　·　低内存设备：" +
                    if (detection?.lowRamDevice == true) "是" else "否" +
                    "　·　捕获源：" +
                    when (activeLevel) {
                        GlassLevel.BACKDROP_LENS -> "layerBackdrop"
                        GlassLevel.HAZE_GLASS, GlassLevel.HAZE_BLUR -> "hazeSource"
                        GlassLevel.MIUIX_BLUR, GlassLevel.SOLID_FALLBACK -> "无"
                    },
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun PerfCard(stats: FrameStats, tick: Int) {
    GlassPanel(
        key = "card.perf",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AppText("渲染性能", AppTypography.CardTitle, AppColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Metric("FPS", "%.1f".format(stats.fps), AppColors.MeSoft)
                Metric("最差单帧", "%.1f ms".format(stats.worstFrameMs), AppColors.TextPrimary)
                Metric("卡顿率", "%.1f%%".format(stats.jankRate * 100f), AppColors.OpponentSoft)
            }
            Spacer(Modifier.height(12.dp))
            AppText(
                text = "累计 ${stats.totalFrames} 帧 · 卡顿 ${stats.jankFrames} 帧 · 采样 ${stats.elapsedMs / 1000}s",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "帧统计每秒只发布一次快照，不会因监控本身引入每帧重组。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun GuardCard(tick: Int) {
    // tick 变化触发重组，从而读到最新的熔断统计
    val stats = remember(tick) { GlassGuard.stats() }

    GlassPanel(
        key = "card.guard",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("熔断状态", AppTypography.CardTitle, AppColors.TextPrimary)
                Box(
                    Modifier
                        .clip(AppShapes.Chip)
                        .background(
                            if (stats.globalTripped) AppColors.OpponentDim else AppColors.MeDim,
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    AppText(
                        text = if (stats.globalTripped) "已降级" else "正常",
                        style = AppTypography.Label,
                        color = if (stats.globalTripped) AppColors.OpponentSoft else AppColors.MeSoft,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AppText(
                text = "累计异常 ${stats.totalFailures} 次　·　渲染风暴探测 ${stats.stormDetections} 次",
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "被永久降级组件 ${stats.deadComponents} / 追踪中 ${stats.trackedComponents}",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.height(10.dp))
            AppText(
                text = "验收标准：连续操作 10 分钟后，上面三个数字应保持为 0。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun Controls() {
    GlassPanel(
        key = "card.controls",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            AppText("档位切换", AppTypography.CardTitle, AppColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            // 5 个选项已超过单行安全宽度 → 走横向滚动（项目约定：>5 个或长度不定用 HorizontalChipRow）
            HorizontalChipRow(fadeColor = Color.Transparent) {
                ActionChip(
                    text = "自动",
                    primary = GlassRuntime.overrideLevel == null,
                ) { GlassRuntime.setOverride(null) }
                ActionChip(
                    text = "Backdrop",
                    primary = GlassRuntime.overrideLevel == GlassLevel.BACKDROP_LENS,
                ) { GlassRuntime.setOverride(GlassLevel.BACKDROP_LENS) }
                ActionChip(
                    text = "Haze",
                    primary = GlassRuntime.overrideLevel == GlassLevel.HAZE_GLASS,
                ) { GlassRuntime.setOverride(GlassLevel.HAZE_GLASS) }
                ActionChip(
                    text = "Haze 模糊",
                    primary = GlassRuntime.overrideLevel == GlassLevel.HAZE_BLUR,
                ) { GlassRuntime.setOverride(GlassLevel.HAZE_BLUR) }
                ActionChip(
                    text = "纯色",
                    primary = GlassRuntime.overrideLevel == GlassLevel.SOLID_FALLBACK,
                ) { GlassRuntime.setOverride(GlassLevel.SOLID_FALLBACK) }
            }
            Spacer(Modifier.height(10.dp))
            AppText(
                text = "Backdrop = 录制背景层 + lens 折射；Haze = 懒捕获 + Glass 材质（折射）；" +
                    "Haze 模糊 = 只模糊不折射（Android 12 的自动档）。三条独立渲染路径，可交叉验证。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionChip(
                    text = if (GlassRuntime.userEnabled) "关闭液态玻璃" else "开启液态玻璃",
                    primary = true,
                ) { GlassRuntime.toggleUserEnabled() }

                ActionChip(text = "重置熔断") { GlassRuntime.resetGuard() }
            }
        }
    }
}

// ---------------------------------------------------------------- 基础零件

@Composable
private fun Metric(label: String, value: String, valueColor: Color) {
    Column {
        AppText(value, AppTypography.Title, valueColor)
        Spacer(Modifier.height(5.dp))
        AppText(label, AppTypography.Label, AppColors.TextTertiary)
    }
}

@Composable
private fun ActionChip(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(AppShapes.Chip)
            .background(if (primary) AppColors.MeDim else AppColors.NeutralFill)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        AppText(
            text = text,
            style = AppTypography.Label,
            color = if (primary) AppColors.MeSoft else AppColors.TextSecondary,
        )
    }
}


// ---------------------------------------------------------------- 本地 ASR 调试

/**
 * 本地语音识别（SenseVoice-Small）调试卡。
 *
 * 模拟器没法"说话"，验证解码链路用文件：把 16k wav 推到 /sdcard/test_recog.wav，
 * 点下面的按钮走与录音完全相同的解码路径（createStream → acceptWaveform → decode）。
 */
@Composable
private fun AsrTestCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember { mutableStateOf("未测试") }
    var running by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }

    GlassPanel(
        key = "stage.asr",
        refraction = RefractionSpec.Subtle,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppText("本地语音识别 · SenseVoice-Small", AppTypography.Title, AppColors.TextPrimary)
            AppText(
                text = if (LocalAsr.isReady) {
                    "引擎就绪（int8 · 离线）"
                } else {
                    LocalAsr.initError?.let { "初始化失败：$it" } ?: "未初始化（首次点按钮时初始化）"
                },
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
            AppText(result, AppTypography.Body, AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionChip(
                    text = if (running) "识别中…" else "解码测试音频",
                    primary = !running,
                    onClick = {
                        if (!running) {
                            running = true
                            result = "初始化/识别中…"
                            thread {
                                val ready = LocalAsr.ensureReady(context)
                                val text = if (ready) {
                                    // App 专属外部目录不需要存储权限；/sdcard 根目录在
                                    // Scoped Storage 下读不了，只作备用
                                    val f = File(context.getExternalFilesDir(null), "test_recog.wav")
                                        .takeIf { it.exists() }
                                        ?: File("/sdcard/test_recog.wav")
                                    LocalAsr.decodeWavFile(f)
                                        ?.ifBlank { "（没识别出内容：samples 或 raw 为空）" }
                                        ?: "解码失败：读不了 wav ${f.absolutePath}"
                                } else {
                                    "引擎初始化失败：${LocalAsr.initError}"
                                }
                                Handler(Looper.getMainLooper()).post {
                                    result = text
                                    running = false
                                }
                            }
                        }
                    },
                )
                ActionChip(
                    text = if (analyzing) "分析中…" else "全局分析 test_global.wav",
                    primary = false,
                    onClick = {
                        if (!analyzing) {
                            analyzing = true
                            result = "VAD 切分 + 逐段识别中…"
                            thread {
                                val ok = LocalAsr.ensureReady(context) &&
                                    com.retropro.feature.voice.GlobalAnalyzer.ensureVadReady(context)
                                val report = if (ok) {
                                    val f = File(context.getExternalFilesDir(null), "test_global.wav")
                                        .takeIf { it.exists() }
                                        ?: File("/sdcard/test_global.wav")
                                    com.retropro.feature.voice.GlobalAnalyzer.analyze(f)
                                } else null
                                val text = if (report != null) {
                                    buildString {
                                        append("时长 ${"%.1f".format(report.durationSec)}s · ")
                                        append("语音 ${"%.1f".format(report.speechSec)}s · ")
                                        append("静音 ${"%.1f".format(report.silenceSec)}s · ")
                                        append("叹气 ${report.sighCount} · 语气词 ${report.fillerCount}\n")
                                        report.items.forEach { it2 ->
                                            append("[").append("%05.1f-%05.1f".format(it2.startSec, it2.endSec))
                                            .append("] ").append(it2.type).append(" 球").append(it2.rallyNo)
                                            .append(" 「").append(it2.text).append("」\n")
                                            .append("    依据: ").append(it2.basis).append("\n")
                                        }
                                    }
                                } else {
                                    "分析失败（引擎/VAD 初始化或读不到文件）"
                                }
                                Handler(Looper.getMainLooper()).post {
                                    result = text
                                    analyzing = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
