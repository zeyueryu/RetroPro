package com.retropro.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retropro.data.model.Rally
import com.retropro.data.model.Scorer
import com.retropro.uikit.theme.AppColors

/**
 * 失分原因标签（对方得分时）。
 *
 * 集合刻意保持"具体且可执行"：像"状态不好"这种标签复盘不出任何东西，
 * 而起因往往能归到体力/判断/脚步里已有的一项。
 */
val LOSS_REASONS = listOf(
    "出界", "下网", "被杀", "跟不上节奏", "判断失误", "体力下降", "发球失误",
)

/** 得分方式（我方得分时） */
val WIN_REASONS = listOf(
    "杀球得分", "对方失误", "网前扑杀", "发球失误",
)

/**
 * 单球的心得 / 标签面板 —— **计分板与逐球复盘页共用**。
 *
 * ## 设计取舍
 *
 * 面板**不阻塞记分流**：可以只点一个标签就关掉（球场上来得及），
 * 也可以写一条长文本（赛后复盘时）。两种粒度共存。
 *
 * 「跳过」**不会删除这一球**，只是不给它心得 ——
 * 统计页的"复盘率"因此能真实反映"有多少球真的想过"。
 * 如果跳过等于删除，用户会倾向于跳过以保持数据好看，指标就废了。
 */
@Composable
fun BoxScope.RallyNotePanel(
    rally: Rally?,
    onDismiss: () -> Unit,
    onSave: (tag: String?, note: String?) -> Unit,
    /** 高频重绘页（计分板）传 false 禁用 lens 折射 */
    useLens: Boolean = true,
) {
    val isLoss = rally?.scorer == Scorer.OPPONENT
    val options = if (isLoss) LOSS_REASONS else WIN_REASONS

    var tag by remember(rally?.id) { mutableStateOf(rally?.reasonTag) }
    var note by remember(rally?.id) { mutableStateOf(rally?.note.orEmpty()) }

    // 遮罩：挡住底下的点击，点空白处等同关闭
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.TextPrimary.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )

    // 同语音面板：内容超出屏幕时按钮会够不到，必须可滚动
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = "第 ${rally?.seq ?: 0} 球 · ${if (isLoss) "对方得分" else "我得分"}",
            subtitle = if (isLoss) "这一球怎么丢的？" else "这一球怎么拿下的？",
            useLens = useLens,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 标签分两行铺开，横屏下也不会挤成一长条
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.take(4).forEach { option ->
                        SelectableChip(
                            text = option,
                            selected = tag == option,
                            onClick = { tag = if (tag == option) null else option },
                        )
                    }
                }
                if (options.size > 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.drop(4).forEach { option ->
                            SelectableChip(
                                text = option,
                                selected = tag == option,
                                onClick = { tag = if (tag == option) null else option },
                            )
                        }
                    }
                }
                AppTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = "这一球的心得",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(
                            text = "保存",
                            onClick = { onSave(tag, note.takeIf { it.isNotBlank() }) },
                        )
                    }
                    SecondaryButton(text = "跳过", onClick = onDismiss)
                }
            }
        }
    }
}
