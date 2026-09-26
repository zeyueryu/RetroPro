package com.retropro.feature.score

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.retropro.uikit.AppText
import com.retropro.uikit.theme.AppTypography
import com.retropro.uikit.theme.AppColors
import kotlin.math.roundToInt

/**
 * 计分板的全局录音按钮 —— 两半屏之间常驻的小圆钮。
 *
 * ## 语义：随时录，事后归位
 *
 * 点击开始录音（变红 + 呼吸动画 + 计时），再点停止。
 * 停止后本地转写（SenseVoice），音频文件与转写文本落盘到 voice_notes/，
 * 并回调给宿主展示归位动作（如「存为最新一球心得」）。
 *
 * ## 位置与性能
 *
 * 放在两半屏之间的中缝上：拇指可及，且不遮挡任何一方的比分。
 * 录音中的呼吸动画只影响本按钮自身的 alpha（draw 阶段），不给计分板添每帧重组。
 */
@Composable
fun GlobalRecordButton(
    recording: Boolean,
    seconds: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onClick() }

    val pulse = if (recording) {
        val t = rememberInfiniteTransition(label = "recPulse")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "recPulseAlpha",
        )
        a
    } else 1f

    Box(
        modifier = modifier
            .alpha(pulse)
            .size(if (recording) 62.dp else 52.dp)
            .clip(CircleShape)
            .background(
                if (recording) AppColors.Opponent
                else AppColors.SurfaceFallback.copy(alpha = 0.72f),
            )
            .clickable {
                if (!hasPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppText("●", AppTypography.Caption, Color.White)
                AppText("${seconds}s", AppTypography.Label, Color.White)
            }
        } else {
            AppText("语音", AppTypography.Label, AppColors.TextSecondary)
        }
    }
}

/** 录音文件的落盘位置与索引。转写文本与文件名一起存，赛后可回听。 */
object VoiceNoteStore {
    private const val DIR = "voice_notes"

    fun dir(context: android.content.Context): java.io.File {
        val d = java.io.File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 保存 wav（由 GlobalRecorder 产出复制过来）+ 追加索引行。返回文件名 */
    fun save(context: android.content.Context, wavFile: java.io.File, transcript: String): String {
        val name = "vn_${System.currentTimeMillis()}.wav"
        wavFile.copyTo(java.io.File(dir(context), name), overwrite = true)
        val index = java.io.File(dir(context), "index.jsonl")
        index.appendText(
            org.json.JSONObject()
                .put("file", name)
                .put("savedAt", System.currentTimeMillis())
                .put("transcript", transcript)
                .toString() + "\n",
        )
        return name
    }

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) "${kb.roundToInt()} KB" else "${(kb / 1024 * 10).roundToInt() / 10.0} MB"
    }
}
