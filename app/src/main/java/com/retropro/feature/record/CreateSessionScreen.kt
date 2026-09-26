package com.retropro.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.model.MatchMode
import com.retropro.data.model.SessionType
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppText
import com.retropro.uikit.AppTextField
import com.retropro.uikit.ChipSelector
import com.retropro.uikit.NumberStepper
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppTypography
import com.retropro.feature.voice.VoiceCapturePanel
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth

/**
 * 补录一场（完整字段）。
 *
 * 与「快速开一场」的分工：
 *  - 快速开：场边用，只要场馆 + 对手，越少字段越好
 *  - 补录：家里用，事后回忆，字段齐全（时长 / 费用 / 体力 / 心得）
 *
 * 两种入口共存而不是合并成一个"智能表单"，是因为**使用场景不同**：
 * 场边手指是脏的、注意力在球上，多一个字段都是劝退；
 * 事后在沙发上补录则会希望一次填全。表单长度无法同时服务两者。
 */
@Composable
fun CreateSessionScreen(
    vm: RecordViewModel,
    autoOpenVoice: Boolean = false,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val venues by vm.recentVenues.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshVenues() }

    var venue by remember { mutableStateOf("") }
    var durationMin by remember { mutableStateOf(90.0) }
    var feeYuan by remember { mutableStateOf(0.0) }
    var stamina by remember { mutableStateOf(3.0) }
    var type by remember { mutableStateOf(SessionType.MATCH) }
    var opponent by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MatchMode.SINGLES) }
    var note by remember { mutableStateOf("") }

    // 语音录入面板 + 它填了哪几个字段（用于在表单上标注「这几个是语音填的」）
    var showVoicePanel by remember { mutableStateOf(false) }

    // 「快速记录 / 语音一句话」进来时自动打开语音面板
    LaunchedEffect(autoOpenVoice) { if (autoOpenVoice) showVoicePanel = true }
    var voiceFilled by remember { mutableStateOf<List<String>>(emptyList()) }

    // 根节点用 Box：语音面板要盖在表单之上
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            AppText("补录一场", AppTypography.Display, AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            AppText(
                text = "赛后回忆录入，字段尽量填全，统计才准",
                style = AppTypography.Caption,
                color = AppColors.TextTertiary,
            )
        }

        SectionCard(
            title = "基本情况",
            subtitle = if (voiceFilled.isEmpty()) {
                "也可以用一句话说完，让语音帮你填"
            } else {
                "已由语音填入：" + voiceFilled.joinToString("、")
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "语音快速录入",
                    onClick = { showVoicePanel = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (venues.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        venues.take(3).forEach { name ->
                            SelectableChip(
                                text = name,
                                selected = venue == name,
                                onClick = { venue = if (venue == name) "" else name },
                            )
                        }
                    }
                }
                AppTextField(value = venue, onValueChange = { venue = it }, label = "场馆")

                NumberStepper(
                    label = "时长",
                    value = durationMin,
                    onValueChange = { durationMin = it },
                    range = 0.0..300.0,
                    step = 15.0,
                    unit = "分",
                    format = { if (it <= 0.0) "—" else it.toInt().toString() },
                )
                NumberStepper(
                    label = "费用",
                    value = feeYuan,
                    onValueChange = { feeYuan = it },
                    range = 0.0..1000.0,
                    step = 5.0,
                    unit = "元",
                    format = { if (it <= 0.0) "—" else it.toInt().toString() },
                )
                NumberStepper(
                    label = "体力消耗",
                    value = stamina,
                    onValueChange = { stamina = it },
                    range = 1.0..5.0,
                    step = 1.0,
                    format = { "${it.toInt()} / 5" },
                )
            }
        }

        SectionCard(
            title = "类型",
            subtitle = if (type == SessionType.MATCH) "比赛会记录对手与胜负" else "训练与自由打不需要对手",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipSelector(
                    options = SessionType.entries.toList(),
                    selected = type,
                    labelOf = { it.label },
                    onSelect = { type = it },
                )
                if (type == SessionType.MATCH) {
                    AppTextField(
                        value = opponent,
                        onValueChange = { opponent = it },
                        label = "对手（可留空）",
                    )
                    ChipSelector(
                        options = MatchMode.entries.toList(),
                        selected = mode,
                        labelOf = { it.label },
                        onSelect = { mode = it },
                    )
                }
            }
        }

        SectionCard(title = "整体心得", subtitle = "这一场打完最想记住的一件事") {
            AppTextField(value = note, onValueChange = { note = it }, label = "比如：第三局体力掉太快")
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            text = if (busy) "保存中…" else "保存",
            enabled = !busy,
            onClick = {
                vm.createSession(
                    venue = venue,
                    durationMin = durationMin.toInt(),
                    feeYuan = feeYuan,
                    stamina = stamina.toInt(),
                    type = type,
                    note = note.takeIf { it.isNotBlank() },
                    opponentName = opponent.takeIf { it.isNotBlank() },
                    mode = mode,
                    onCreated = onCreated,
                )
            },
        )
        SecondaryButton(text = "取消", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    if (showVoicePanel) {
        VoiceCapturePanel(
            onDismiss = { showVoicePanel = false },
            onApply = { draft ->
                // 只填**明确抽到**的字段；抽不到的保持原值，绝不猜（见 VoiceDraft）
                draft.durationMin?.let { durationMin = it.toDouble() }
                draft.feeYuan?.let { feeYuan = it }
                draft.venue?.let { venue = it }
                voiceFilled = draft.filledFields
                showVoicePanel = false
            },
        )
    }
    }
}
