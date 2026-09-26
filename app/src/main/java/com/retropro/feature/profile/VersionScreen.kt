package com.retropro.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.retropro.BuildConfig
import com.retropro.feature.voice.AsrModelPlugin
import com.retropro.feature.voice.GlobalAnalyzer
import com.retropro.feature.voice.LocalAsr
import com.retropro.uikit.AppText
import com.retropro.uikit.MiuixCard
import com.retropro.uikit.MiuixPopupScope
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.overlay.OverlayDialog

// ---------------------------------------------------------------- 链接常量
//
// 全部为**实测可用**的地址（GitHub 的取自其 API 的 browser_download_url，不是拼凑的）：
//  - APK：本仓库的 Releases（GitHub / AtomGit 双渠道）
//  - 模型：上游 k2-fsa 官方发布页 + HuggingFace 国内镜像站

private const val GITHUB_RELEASES = "https://github.com/zeyueryu/RetroPro/releases"
private const val ATOMGIT_RELEASES = "https://atomgit.com/zeyueryu/RetroPro/releases"

/** 上游官方模型发布页（tag `asr-models`），内含 int8 包与 silero_vad.onnx */
private const val MODEL_GITHUB_PAGE = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models"

/** HuggingFace 国内镜像站上的同名模型仓（已验证 200） */
private const val MODEL_MIRROR_PAGE =
    "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

/** 弹卡片圆角，对齐 [AppShapes.Card] 的 26dp */
private val DialogCornerRadius = 26.dp

/** hero 大字相对 AppTypography.Display 的倍数（32sp → 约 51sp）。不写死 sp */
private const val HERO_SCALE = 1.6f

// ---------------------------------------------------------------- 状态机

/** 版本检查状态机 */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** 有新版本；[tag] 为远端 tag 原文（如 v1.2.0） */
    data class UpdateAvailable(val tag: String) : UpdateState

    data class Failed(val failure: UpdateFailure) : UpdateState
}

/**
 * 失败原因。存**枚举**而不是字符串：文案集中在本文件的 [UpdateFailure.message] 一处生成，
 * 卡面小字与弹卡片 summary 共用同一份，不会两处走样。
 */
private enum class UpdateFailure {
    NO_NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    NOT_FOUND,
    SERVER_ERROR,
    UNPARSEABLE,
    UNKNOWN,
}

/** 弹卡片语义：两个入口（检查更新 / 更新日志）共用同一套卡片 */
private enum class DialogMode { UPDATE, RELEASE_NOTES }

/**
 * 语音模型插件状态。
 *
 * 模型（约 229 MB）不进 APK，由用户按需下载 —— 这样升级只需装几十兆的小包。
 */
private sealed interface ModelState {
    /** 首帧，正在读磁盘判断是否已安装 */
    data object Probing : ModelState
    data object NotInstalled : ModelState
    data object Installed : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

private fun UpdateFailure.message(): String = when (this) {
    UpdateFailure.NO_NETWORK -> "无法连接网络，检查联网后重试"
    UpdateFailure.TIMEOUT -> "连接超时，请稍后重试"
    UpdateFailure.RATE_LIMITED -> "GitHub 请求过于频繁（未登录时每小时 60 次上限），请稍后再试"
    // 仓库还没有任何 Release 时 GitHub 就返回 404 —— 这是正常状态，不是故障
    UpdateFailure.NOT_FOUND -> "还没有发布正式版本，可先到发布页看看"
    UpdateFailure.SERVER_ERROR -> "GitHub 服务暂时不可用，请稍后再试"
    UpdateFailure.UNPARSEABLE -> "无法识别远端版本号，可到渠道页自行查看"
    UpdateFailure.UNKNOWN -> "检查失败，请稍后重试"
}

private fun Throwable.toFailure(): UpdateFailure = when (this) {
    is UpdateChecker.HttpError -> when (code) {
        403 -> UpdateFailure.RATE_LIMITED
        404 -> UpdateFailure.NOT_FOUND
        in 500..599 -> UpdateFailure.SERVER_ERROR
        else -> UpdateFailure.UNKNOWN
    }
    // 飞行模式 / 断网 / DNS 解析失败都落在这里
    is java.net.UnknownHostException -> UpdateFailure.NO_NETWORK
    is java.net.ConnectException -> UpdateFailure.NO_NETWORK
    is java.net.NoRouteToHostException -> UpdateFailure.NO_NETWORK
    is java.net.SocketTimeoutException -> UpdateFailure.TIMEOUT
    is java.io.InterruptedIOException -> UpdateFailure.TIMEOUT
    else -> UpdateFailure.UNKNOWN
}

// ---------------------------------------------------------------- 页面

/**
 * 「我的」→「版本与更新」二级页（仿 ColorOS「软件更新」的结构）。
 *
 * ## 版式
 *
 *  **hero**：居中三行 —— 大字版本号 / RetroPro / 设备型号（纯文字，跟随 App 主题）
 *  **当前版本卡**：「检查更新」（联网比对版本）/「更新日志」
 *  **语音模型卡**：插件状态 + 下载 / 取消 / 删除（约 229 MB 的模型不进 APK，按需下载）
 *  **弹卡片**：MIUIX [OverlayDialog]，内含「安装包」与「语音模型」两组下载入口
 *
 * 页面用 [SettingsScaffold] 的 `compact` 模式：顶部是「返回箭头 + 小页名」，
 * 因为这里要让**版本号独占视觉焦点**，32sp 大标题会跟它抢注意力。
 *
 * ## ⚠️ [MiuixPopupScope] 为什么必须在**整页最外层**
 *
 * MIUIX 的 `Overlay*` 系列依赖 `Scaffold` 提供的 `MiuixPopupHost`，否则**不渲染且不报错**。
 * 本项目主容器是自研 `GlassScene`（不能换成 MIUIX `Scaffold`，否则玻璃自引用崩溃），
 * 所以用 [MiuixPopupScope] 挂一个最小壳。三种摆法都会坏：
 *  - 放进 `SettingsScaffold` 的 content → 那里是 `verticalScroll`（高度无界），
 *    内层 `Box(fillMaxSize)` 会塌陷，且弹层可能被滚动容器裁剪；
 *  - 只包某个按钮 → Scaffold 内容槽是 subcompose、会 fillMaxSize，按钮被撑成整屏；
 *  - 嵌套两层 → 里层同样撑满，等价于上一条。
 */
@Composable
fun VersionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialogMode by remember { mutableStateOf(DialogMode.UPDATE) }
    var dialogVisible by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }

    var modelState by remember { mutableStateOf<ModelState>(ModelState.Probing) }
    var installJob by remember { mutableStateOf<Job?>(null) }

    // 首帧探测模型是否已安装（读 3 个文件长度，放 IO 线程，避免任何主线程 IO）
    LaunchedEffect(Unit) {
        modelState = withContext(Dispatchers.IO) {
            if (AsrModelPlugin.isInstalled(context)) ModelState.Installed else ModelState.NotInstalled
        }
    }

    /** 检查更新（页面按钮共用；结果写回 [state]，卡片按状态自动刷新） */
    fun runCheck() {
        dialogMode = DialogMode.UPDATE
        dialogVisible = true
        state = UpdateState.Checking
        scope.launch {
            state = UpdateChecker.fetchLatestTag().fold(
                onSuccess = { tag ->
                    when (val verdict = compareVersion(tag, BuildConfig.VERSION_NAME)) {
                        is VersionVerdict.Newer -> UpdateState.UpdateAvailable(tag)
                        VersionVerdict.UpToDate -> UpdateState.UpToDate
                        VersionVerdict.Unparseable -> UpdateState.Failed(UpdateFailure.UNPARSEABLE)
                    }
                },
                onFailure = { UpdateState.Failed(it.toFailure()) },
            )
        }
    }

    /** 应用内下载模型（多源自动故障转移，见 [AsrModelPlugin]） */
    fun installModel() {
        installJob = scope.launch {
            modelState = ModelState.Downloading(0, AsrModelPlugin.TOTAL_BYTES)
            // 回调来自 IO 线程：写 Compose 状态是线程安全的（快照写入），且每 1MB 才回调一次
            val result = AsrModelPlugin.install(context) { done, total, _ ->
                modelState = ModelState.Downloading(done, total)
            }
            modelState = result.fold(
                onSuccess = {
                    // 模型换了必须让引擎重建，否则还在用旧文件路径
                    LocalAsr.releaseRecognizer()
                    GlobalAnalyzer.releaseVad()
                    ModelState.Installed
                },
                onFailure = { ModelState.Failed(it.message ?: "下载失败") },
            )
        }
    }

    /** 取消下载：协程取消会清掉半截的 `.part`，已下好的文件保留（下次省流量） */
    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        modelState = ModelState.NotInstalled
    }

    /** 删除已下载模型（随时能重新下载，属"可从界面恢复"，按项目约定不拦二次确认） */
    fun removeModel() {
        scope.launch {
            withContext(Dispatchers.IO) { AsrModelPlugin.remove(context) }
            LocalAsr.releaseRecognizer()
            GlobalAnalyzer.releaseVad()
            modelState = ModelState.NotInstalled
        }
    }

    /** 用浏览器打开链接；设备上没有可用浏览器时降级为提示文本，不崩溃 */
    fun open(url: String) {
        openFailed = false
        runCatching { uriHandler.openUri(url) }.onFailure { openFailed = true }
    }

    MiuixPopupScope(modifier = Modifier.fillMaxSize()) {
        SettingsScaffold(
            title = "版本与更新",
            subtitle = "", // compact 模式不渲染副标题，见 SettingsScaffold 的参数说明
            onBack = onBack,
            compact = true,
        ) {
            VersionHero()

            VersionCard(
                state = state,
                onCheck = { runCheck() },
                onOpenLog = {
                    dialogMode = DialogMode.RELEASE_NOTES
                    dialogVisible = true
                },
            )

            AsrModelCard(
                modelState = modelState,
                onInstall = { installModel() },
                onCancel = { cancelInstall() },
                onRemove = { removeModel() },
            )

            if (openFailed) {
                AppText(
                    text = "没有可用浏览器，请手动访问 github.com/zeyueryu/RetroPro/releases",
                    style = AppTypography.Caption,
                    color = AppColors.OpponentSoft,
                )
            }
        }

        // 弹卡片与 SettingsScaffold 同层，都在 MiuixPopupScope 的子树内
        VersionDialogCard(
            mode = dialogMode,
            visible = dialogVisible,
            state = state,
            modelState = modelState,
            onDismiss = { dialogVisible = false },
            onInstallModel = {
                installModel()
                // 进度由页面卡片持续承载，弹窗关掉即可 —— 弹窗是临时的，会消失
                dialogVisible = false
            },
            onOpenGithubApk = { open(GITHUB_RELEASES) },
            onOpenAtomgitApk = { open(ATOMGIT_RELEASES) },
            onOpenModelGithub = { open(MODEL_GITHUB_PAGE) },
            onOpenModelMirror = { open(MODEL_MIRROR_PAGE) },
        )
    }
}

// ---------------------------------------------------------------- 各区块

/**
 * hero 区：居中三行 —— 大字版本号 / RetroPro / 设备型号。
 *
 * 字号用**相对倍数**放大（[HERO_SCALE]），不写死 sp —— 见项目的字号规则与
 * `RecordScreen` 里 `AppTypography.Title.fontSize * 1.35f` 的先例。
 */
@Composable
private fun VersionHero() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(
            text = BuildConfig.VERSION_NAME,
            style = AppTypography.Display.copy(
                fontSize = AppTypography.Display.fontSize * HERO_SCALE,
            ),
            color = AppColors.TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        AppText("RetroPro", AppTypography.Title, AppColors.TextSecondary)
        Spacer(Modifier.height(2.dp))
        AppText(deviceLabel(), AppTypography.Caption, AppColors.TextTertiary)
    }
}

/** 「当前版本」卡：检查结果 + 两个动作 */
@Composable
private fun VersionCard(
    state: UpdateState,
    onCheck: () -> Unit,
    onOpenLog: () -> Unit,
) {
    MiuixCard(
        title = "当前版本",
        subtitle = "${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 结果小字：弹卡片关掉后结果不丢（Idle / Checking 不显示）
            val result: Pair<String, Color>? = when (state) {
                UpdateState.Idle, UpdateState.Checking -> null
                UpdateState.UpToDate -> "已是最新版本" to AppColors.SuccessSoft
                is UpdateState.UpdateAvailable -> "发现新版本 ${state.tag}" to AppColors.MeSoft
                is UpdateState.Failed -> state.failure.message() to AppColors.OpponentSoft
            }
            result?.let { (text, color) -> AppText(text, AppTypography.Caption, color) }

            PrimaryButton(
                text = if (state == UpdateState.Checking) "检查中…" else "检查更新",
                enabled = state != UpdateState.Checking,
                onClick = onCheck,
            )
            SecondaryButton(
                text = "更新日志",
                onClick = onOpenLog,
                modifier = Modifier.fillMaxWidth(),
            )
            AppText(
                text = "更新只在点击时联网检查，其余功能全部离线。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/**
 * 「语音模型」卡：插件状态 + 下载 / 取消 / 删除。
 *
 * ⚠️ 这里**不用 [PrimaryButton]**：项目规则是「全页只允许一个主行动按钮」，
 * 本页的 primary 已给「检查更新」；安装入口的主按钮在弹卡片里（用户指定的位置）。
 */
@Composable
private fun AsrModelCard(
    modelState: ModelState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    MiuixCard(
        title = "语音模型",
        subtitle = "约 ${AsrModelPlugin.APPROX_MB} MB · 不进安装包，按需下载",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (modelState) {
                ModelState.Probing -> AppText(
                    "正在检查…",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )

                ModelState.NotInstalled -> AppText(
                    "未安装 —— 语音识别会退到系统识别器",
                    AppTypography.Caption,
                    AppColors.OpponentSoft,
                )

                ModelState.Installed -> AppText(
                    "已安装 · 可离线识别",
                    AppTypography.Caption,
                    AppColors.SuccessSoft,
                )

                is ModelState.Downloading -> {
                    AppText(
                        "下载中 ${progressPercent(modelState.done, modelState.total)}%" +
                            " · ${fmtMb(modelState.done)} / ${fmtMb(modelState.total)} MB",
                        AppTypography.Caption,
                        AppColors.MeSoft,
                    )
                    ProgressBar(fraction = progressFraction(modelState.done, modelState.total))
                }

                is ModelState.Failed -> AppText(
                    modelState.reason,
                    AppTypography.Caption,
                    AppColors.OpponentSoft,
                )
            }

            when (modelState) {
                is ModelState.Downloading -> SecondaryButton(
                    text = "取消下载",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )

                ModelState.Installed -> SecondaryButton(
                    text = "删除模型",
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                )

                ModelState.NotInstalled, is ModelState.Failed -> SecondaryButton(
                    text = "下载语音模型",
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                )

                ModelState.Probing -> Unit
            }

            AppText(
                text = "模型只需下载一次；之后升级只装几十兆的小安装包。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/** 细进度条：两个 Box 叠加，零第三方组件、零 API 风险 */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(AppShapes.Chip)
            .background(AppColors.NeutralFill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(AppShapes.Chip)
                .background(AppColors.Me),
        )
    }
}

// ---------------------------------------------------------------- 弹卡片

/**
 * 两个入口共用的 MIUIX 弹卡片（[OverlayDialog]，依赖外层 [MiuixPopupScope] 的 popupHost）。
 *
 * 分两组下载入口：
 *  - **安装包**：GitHub / AtomGit（浏览器打开 Releases）
 *  - **语音模型**：应用内下载（带进度、多源故障转移）+ GitHub / 镜像站两个兜底链接
 *
 * 用独立的 `visible`（显隐）+ `mode`（内容）两个状态，而不是"可空对话框对象"：
 * 关闭动画期间若内容源变 null，标题会在退场时突然跳变。
 *
 * 内部**不再套 `GlassPanel` / `MiuixCard`**：`OverlayDialog` 自身就是卡片载体；
 * 叠玻璃会二次材质（且 Popup 是独立窗口、拿不到折射源），套 `MiuixCard` 则是"卡中卡"。
 */
@Composable
private fun VersionDialogCard(
    mode: DialogMode,
    visible: Boolean,
    state: UpdateState,
    modelState: ModelState,
    onDismiss: () -> Unit,
    onInstallModel: () -> Unit,
    onOpenGithubApk: () -> Unit,
    onOpenAtomgitApk: () -> Unit,
    onOpenModelGithub: () -> Unit,
    onOpenModelMirror: () -> Unit,
) {
    val isUpdate = mode == DialogMode.UPDATE
    val title = if (isUpdate) "检查更新" else "更新日志"

    val (summary, summaryColor) = when {
        !isUpdate -> "在 GitHub 或 AtomGit 查看完整发布说明" to AppColors.TextSecondary
        state is UpdateState.Checking -> "正在检查…" to AppColors.TextSecondary
        state is UpdateState.UpToDate -> "当前已是最新版本" to AppColors.SuccessSoft
        state is UpdateState.UpdateAvailable -> "发现新版本 ${state.tag}" to AppColors.MeSoft
        state is UpdateState.Failed -> state.failure.message() to AppColors.OpponentSoft
        else -> "点击下方渠道查看发布页面" to AppColors.TextSecondary
    }

    OverlayDialog(
        show = visible,
        onDismissRequest = onDismiss,
        title = title,
        titleColor = AppColors.TextPrimary,
        summary = summary,
        summaryColor = summaryColor,
        // 显式给项目自己的实底卡色，保证配色跟 App 主题而不是 MIUIX 默认表面
        backgroundColor = AppColors.SurfaceFallback,
        cornerRadius = DialogCornerRadius,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- 安装包
            SectionLabel("安装包（APK）")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        text = "GitHub",
                        onClick = onOpenGithubApk,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        text = "AtomGit",
                        onClick = onOpenAtomgitApk,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- 语音模型（插件）
            SectionLabel("语音模型（约 ${AsrModelPlugin.APPROX_MB} MB）")
            when (modelState) {
                ModelState.Installed -> AppText(
                    "已安装，无需重复下载",
                    AppTypography.Caption,
                    AppColors.SuccessSoft,
                )

                is ModelState.Downloading -> {
                    AppText(
                        "下载中 ${progressPercent(modelState.done, modelState.total)}%",
                        AppTypography.Caption,
                        AppColors.MeSoft,
                    )
                    ProgressBar(fraction = progressFraction(modelState.done, modelState.total))
                }

                is ModelState.Failed -> AppText(
                    modelState.reason,
                    AppTypography.Caption,
                    AppColors.OpponentSoft,
                )

                ModelState.NotInstalled, ModelState.Probing -> Unit
            }

            PrimaryButton(
                text = when (modelState) {
                    is ModelState.Downloading ->
                        "下载中 ${progressPercent(modelState.done, modelState.total)}%"
                    ModelState.Installed -> "重新下载模型"
                    else -> "应用内下载"
                },
                // 下载中/探测中不可再触发；进度由页面卡片承载
                enabled = modelState !is ModelState.Downloading && modelState != ModelState.Probing,
                onClick = onInstallModel,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        text = "GitHub 官方",
                        onClick = onOpenModelGithub,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.weight(1f)) {
                    SecondaryButton(
                        text = "镜像站",
                        onClick = onOpenModelMirror,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AppText(
                text = "模型只需下载一次；之后升级只装上面的小安装包。",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/** 弹卡片内的分组小标题 */
@Composable
private fun SectionLabel(text: String) {
    AppText(text, AppTypography.Label, AppColors.TextTertiary)
}

// ---------------------------------------------------------------- 工具

/**
 * 设备型号行，如 `realme GT8 Pro`。
 *
 * `Build.MODEL` 在部分 ROM 上已含厂商名，部分只有型号（`RMX5200`）——
 * 用 startsWith 去重，避免拼出「realme realme …」。厂商名首字母大写（MANUFACTURER 常为小写）。
 */
private fun deviceLabel(): String {
    val maker = android.os.Build.MANUFACTURER
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val model = android.os.Build.MODEL.trim()
    return when {
        model.isEmpty() && maker.isEmpty() -> "未知设备"
        model.isEmpty() -> maker
        maker.isEmpty() || maker.equals("unknown", ignoreCase = true) -> model
        model.startsWith(maker, ignoreCase = true) -> model
        else -> "$maker $model"
    }
}

private fun progressFraction(done: Long, total: Long): Float =
    if (total <= 0L) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

private fun progressPercent(done: Long, total: Long): Int =
    (progressFraction(done, total) * 100).toInt()

private fun fmtMb(bytes: Long): Long = bytes / 1024 / 1024
