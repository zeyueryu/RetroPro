package com.retropro.uikit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 文本的唯一出口 —— **直接转发给 MIUIX 的 `Text`**。
 *
 * 为什么不直接用 `BasicText`：MIUIX 的 `Text` 会走 HyperOS 的排版参数
 * （行高、字距、字体族回退），是"整体风格采用 MIUIX"的一部分。
 *
 * 这层薄包装的唯一目的是让调用点可以用位置参数 `Text(text, style, color)`，
 * 并且把"字体/样式从哪来"这件事收敛到一个文件里 —— 换 MiSans 时只改这里。
 */
@Composable
fun AppText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    MiuixText(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}
