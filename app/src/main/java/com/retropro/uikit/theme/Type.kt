package com.retropro.uikit.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 排版规范 —— **别名映射到 MIUIX 自己的 `TextStyles`**。
 *
 * ## 为什么改成映射
 *
 * 早期这里是一张自定的字号表（30/20/16/14/12/10.5sp），再用 `BasicText` 渲染。
 * 那只是"看起来像"，并不是"采用 MIUIX"：MIUIX 的字号、行高、字重、
 * 字体族回退都由 [MiuixTheme.textStyles] 统一给出，自定一套等于把设计语言劈成两半。
 *
 * 现在 AppTypography 只是**别名**：业务代码继续写 `AppTypography.Display`，
 * 但真正生效的是 MIUIX 的 `title1 / title2 / title3 / title4 / footnote1 / footnote2 / body2`，
 * MIUIX 升级时会自动跟着变。
 *
 * ## 档位字号（MIUIX 0.9.4 实测，供选档时参考）
 *
 * `title1 = 32sp` / `title2 = 24sp` / `title3 = 20sp` / `title4 = 18sp` /
 * `body1 = 16sp` / `body2 = 14sp` / `footnote1 = 13sp` / `footnote2 = 11sp`。
 *
 * ⚠️ 需要"比现有档位更大"的文字时，**先看这里有没有现成的档** ——
 * `headline` / `subtitle` / `paragraph` / `main` / `button` 这几档目前未暴露，
 * 用到再补映射，**不要就地写 `copy(fontSize = 24.sp)` 硬编码**：
 * 写死就脱离 MIUIX 体系了，库升级时这一处不会跟着变。
 * 另外 MIUIX 的 `fontWeight` / `lineHeight` 全档位都是 null（由字体族决定），
 * 也不要人为补，否则与其它文字的字重节奏对不上。
 *
 * ## MiSans 接入
 *
 * MIUIX 的默认字体族已经是 HyperOS 的排版体系。若要显式换成 MiSans，
 * 正确做法不是在业务代码里塞 `fontFamily`，而是**整体替换字型**：
 *
 * ```
 * CompositionLocalProvider(
 *     LocalMiuixTextStyles provides MiuixTheme.textStyles.copy(
 *         title1 = MiuixTheme.textStyles.title1.copy(fontFamily = MiSansFamily),
 *         // …其余同理
 *     )
 * ) { AppTheme { … } }
 * ```
 *
 * 字体文件放在 `app/src/main/res/font/`，`MiSansFamily` 用 `FontFamily(Font(...))` 构造。
 * 目前字体文件未就位，因此沿用 MIUIX 默认族，保证工程始终可编译可运行。
 */
object AppTypography {

    /** 页面大标题 —— 首页"本月已打 11 场" */
    val Display: TextStyle @Composable get() = MiuixTheme.textStyles.title1

    /** 卡片内的大数字 */
    val Title: TextStyle @Composable get() = MiuixTheme.textStyles.title3

    /** 卡片标题 */
    val CardTitle: TextStyle @Composable get() = MiuixTheme.textStyles.title4

    /** 引导语 / 每日格言 —— 次级标题，介于 [CardTitle] 与大数字 [Title] 之间 */
    val Lead: TextStyle @Composable get() = MiuixTheme.textStyles.title2

    /** 正文 */
    val Body: TextStyle @Composable get() = MiuixTheme.textStyles.body2

    /** 标签 / 按钮字 */
    val Label: TextStyle @Composable get() = MiuixTheme.textStyles.footnote1

    /** 辅助说明文字 */
    val Caption: TextStyle @Composable get() = MiuixTheme.textStyles.footnote2
}
