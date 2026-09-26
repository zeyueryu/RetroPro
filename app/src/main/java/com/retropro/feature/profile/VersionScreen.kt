package com.retropro.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.retropro.BuildConfig
import com.retropro.uikit.AppText
import com.retropro.uikit.MiuixCard
import com.retropro.uikit.MiuixPopupScope
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppTypography
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.overlay.OverlayDialog

// ---------------------------------------------------------------- 常量

/** 两个更新渠道。链接改这里即可，页面与弹卡片都从这里取 */
private const val GITHUB_RELEASES = "https://github.com/zeyueryu/RetroPro/releases"
private const val ATOMGIT_RELEASES = "https://atomgit.com/zeyueryu/RetroPro/releases"

/** 弹卡片圆角，对齐 [com.retropro.uikit.theme.AppShapes.Card] 的 26dp */
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
 *  **hero**：居中三行 —— 大字版本号 / RetroPro / 设备型号（纯文字，跟随 App 主题，不做深色沉浸）
 *  **卡片**：「当前版本」+ 检查结果小字 + 「检查更新」/「更新日志」两个按钮
 *  **弹卡片**：MIUIX [OverlayDialog]，两个渠道（GitHub / AtomGit）都用浏览器打开
 *  页面用 [SettingsScaffold] 的 `compact` 模式：顶部是「返回箭头 + 小页名」，
 *  因为这里要让**版本号独占视觉焦点**，32sp 大标题会跟它抢注意力。
 *
 * ## ⚠️ [MiuixPopupScope] 为什么必须在**整页最外层**
 *
 * MIUIX 的 `Overlay*` 系列依赖 `Scaffold` 提供的 `MiuixPopupHost`，否则**不渲染且不报错**。
 * 本项目主容器是自研 `GlassScene`（不能换成 MIUIX `Scaffold`，否则玻璃自引用崩溃），
 * 所以用 [MiuixPopupScope] 挂一个最小壳。三种摆法都会坏：
 *  - 放进 `SettingsScaffold` 的 content → 那里是 `verticalScroll`（高度无界），
 *    内层 `Box(fillMaxSize)` 会塌陷，且弹层可能被滚动容器裁剪；
 *  - 只包「检查更新」按钮 → Scaffold 内容槽是 subcompose、会 fillMaxSize，按钮被撑成整屏；
 *  - 嵌套两层 → 里层同样撑满，等价于上一条。
 */
@Composable
fun VersionScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialogMode by remember { mutableStateOf(DialogMode.UPDATE) }
    var dialogVisible by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }

    /** 检查更新（首次点击与弹窗内「重试」共用；结果写回 [state]，卡片按状态自动刷新） */
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

    /** 用浏览器打开渠道页；设备上没有可用浏览器时降级为提示文本，不崩溃 */
    fun openChannel(url: String) {
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

            MiuixCard(
                title = "当前版本",
                subtitle = "${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 结果小字：弹卡片关掉后结果不丢（Idle / Checking 不显示）
                    val result: Pair<String, Color>? = when (val s = state) {
                        UpdateState.Idle, UpdateState.Checking -> null
                        UpdateState.UpToDate -> "已是最新版本" to AppColors.SuccessSoft
                        is UpdateState.UpdateAvailable -> "发现新版本 ${s.tag}" to AppColors.MeSoft
                        is UpdateState.Failed -> s.failure.message() to AppColors.OpponentSoft
                    }
                    result?.let { (text, color) ->
                        AppText(text, AppTypography.Caption, color)
                    }

                    PrimaryButton(
                        text = if (state == UpdateState.Checking) "检查中…" else "检查更新",
                        enabled = state != UpdateState.Checking,
                        onClick = { runCheck() },
                    )
                    SecondaryButton(
                        text = "更新日志",
                        onClick = {
                            dialogMode = DialogMode.RELEASE_NOTES
                            dialogVisible = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (openFailed) {
                        AppText(
                            text = "没有可用浏览器，请手动访问 github.com/zeyueryu/RetroPro/releases",
                            style = AppTypography.Caption,
                            color = AppColors.OpponentSoft,
                        )
                    }
                    AppText(
                        text = "更新只在点击时联网检查，其余功能全部离线。",
                        style = AppTypography.Caption,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }

        // 弹卡片与 SettingsScaffold 同层，都在 MiuixPopupScope 的子树内
        VersionDialogCard(
            mode = dialogMode,
            visible = dialogVisible,
            state = state,
            onDismiss = { dialogVisible = false },
            onRetry = { runCheck() },
            onOpenGitHub = { openChannel(GITHUB_RELEASES) },
            onOpenAtomGit = { openChannel(ATOMGIT_RELEASES) },
        )
    }
}

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

// ---------------------------------------------------------------- 弹卡片

/**
 * 两个入口共用的 MIUIX 弹卡片（[OverlayDialog]，依赖外层 [MiuixPopupScope] 的 popupHost）。
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
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenAtomGit: () -> Unit,
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
            // 失败态额外给「重试」：检查失败时用户最想做的就是再试一次
            if (isUpdate && state is UpdateState.Failed) {
                PrimaryButton(text = "重试", onClick = onRetry)
            }
            PrimaryButton(text = "在 GitHub 打开", onClick = onOpenGitHub)
            SecondaryButton(
                text = "在 AtomGit 打开",
                onClick = onOpenAtomGit,
                modifier = Modifier.fillMaxWidth(),
            )
            AppText(
                text = "github.com/zeyueryu/RetroPro/releases",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }
    }
}
