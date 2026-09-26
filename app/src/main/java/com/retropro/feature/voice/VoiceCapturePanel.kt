package com.retropro.feature.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppText
import com.retropro.uikit.AppTextField
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.pressScale
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography

/** 示例句子。用户第一句往往不知道怎么说，给一个可以直接试的。 */
private const val SAMPLE_SENTENCE = "今天在城东体育馆打了两小时花了四十块"

/**
 * 语音快速录入面板（设计方案 §7.2）。
 *
 * ## 流程
 *
 * ```
 * 点录音 → 本地 SenseVoice（离线；系统识别兜底）
 *        → 得到文本："今天在城东体育馆打了两小时花了四十块"
 *        → VoiceParser 本地抽取：时长 120 / 费用 40 / 场馆 城东体育馆
 *        → 展示"识别到什么"，用户核对
 *        → 点「填入表单」才真正写进表单（**不直接落库**）
 * ```
 *
 * ## 与整场分析的关系
 *
 * 整场语音的全局研判分析已移至**计分板页**（比赛中才用得上，
 * 入口在计分板顶条的「整场语音」chip，见 [GlobalVoicePanel]）。
 * 本面板只做单句快速录入。
 */
@Composable
fun BoxScope.VoiceCapturePanel(
    onDismiss: () -> Unit,
    onApply: (VoiceDraft) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context) { VoiceInputController(context) }
    DisposableEffect(controller) {
        onDispose { controller.destroy() }
    }

    val state = controller.state
    var typed by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) controller.start()
    }

    // 遮罩：点空白关闭
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.TextPrimary.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss),
    )

    // 面板内容必须可滚动：小屏 / 大字号下「填入表单」会被截在屏幕外，
    // 而截断的按钮既点不到也不会提示 —— 实测在这个密度上就发生了。
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            // 底部净空让卡片停在玻璃导航栏上方；净空不是滚动避让，卡片内部照常滚动
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + navBarClearance())
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = "语音快速录入",
            subtitle = "说一句就行，例如「在城东体育馆打了两小时花了四十块」",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                // ---- 识别原文（离线引擎没有中间结果，录完才出全文）
                val heardText = if (state is VoiceState.Done) state.draft.raw else ""
                if (heardText.isNotBlank()) {
                    AppText(
                        text = "「$heardText」",
                        style = AppTypography.CardTitle,
                        color = AppColors.TextPrimary,
                    )
                }

                when (state) {
                    is VoiceState.Listening -> AppText(
                        text = "正在听… ${state.seconds}s（说完点停止）",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary,
                    )

                    VoiceState.Recognizing -> AppText(
                        text = "本地识别中，几秒钟…",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary,
                    )

                    is VoiceState.Failed -> AppText(
                        text = state.message,
                        style = AppTypography.Caption,
                        color = AppColors.OpponentSoft,
                    )

                    is VoiceState.Done -> {
                        DraftPreview(state.draft)
                    }

                    VoiceState.Idle -> AppText(
                        text = if (controller.isAvailable()) {
                            if (controller.localReady) "离线识别 · 点下面的按钮开始说话"
                            else "点下面的按钮开始说话"
                        } else {
                            "这台设备没有可用的语音识别服务，请用下方文字输入"
                        },
                        style = AppTypography.Caption,
                        color = AppColors.TextTertiary,
                    )
                }

                // ---- 录音按钮
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val listening = state is VoiceState.Listening
                    // 按钮底色可在设置里自定义（HyperOS 蓝/绿/橙/紫/粉）；
                    // 录音中固定用对方橙 —— 状态色不跟自定义走，避免"红色=停止"被改掉
                    val btnColor = com.retropro.util.AppPrefs
                        .recordButtonColor(context)
                    val recInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(recInteraction, pressedScale = 0.94f)
                            .size(72.dp)
                            .background(
                                color = if (listening) AppColors.Opponent else btnColor,
                                shape = CircleShape,
                            )
                            .clickable(
                                interactionSource = recInteraction,
                                indication = null,
                            ) {
                                when {
                                    listening -> controller.stop()
                                    !hasPermission ->
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

                                    else -> controller.start()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            text = if (listening) "停止" else "录音",
                            style = AppTypography.Body,
                            color = Color.White,
                        )
                    }
                }

                // ---- 文字入口（同一个解析器）
                AppTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = "或直接输入一句话",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (typed.isNotBlank()) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton(
                                text = "解析这句话",
                                onClick = { controller.submitText(typed) },
                            )
                        }
                    } else {
                        // 可点的示例：语音录入是「教一次就会用」的功能，
                        // 给一个能立刻试的句子，比只写一句提示有效得多
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(
                                text = "用示例试一下",
                                onClick = { controller.submitText(SAMPLE_SENTENCE) },
                            )
                        }
                    }
                }

                // ---- 动作
                if (state is VoiceState.Done) {
                    PrimaryButton(
                        text = "填入表单",
                        onClick = { onApply(state.draft) },
                    )
                }
                SecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** 草稿预览：明确列出"识别到了什么"，空的一栏也写出来，避免用户以为漏了 */
@Composable
private fun DraftPreview(draft: VoiceDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DraftLine("时长", draft.durationMin?.let { "$it 分钟" })
        DraftLine("费用", draft.feeYuan?.let { if (it % 1.0 == 0.0) "¥${it.toInt()}" else "¥$it" })
        DraftLine("场馆", draft.venue)
        Spacer(Modifier.height(2.dp))
        AppText(
            text = if (draft.isEmpty) {
                "没识别出可填的字段，请手动填写"
            } else {
                "以上是识别结果，**不会自动保存**，请核对后再填表"
            },
            style = AppTypography.Caption,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun DraftLine(label: String, value: String?) {
    Row(
        Modifier
            .background(AppColors.NeutralFill, AppShapes.Chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AppText(label, AppTypography.Body, AppColors.TextSecondary)
        AppText(
            text = value ?: "—",
            style = AppTypography.Body,
            color = if (value != null) AppColors.MeSoft else AppColors.TextTertiary,
        )
    }
}
