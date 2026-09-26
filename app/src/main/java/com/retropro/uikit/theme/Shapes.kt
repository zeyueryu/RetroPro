package com.retropro.uikit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 形状规范（对应《开源库选型与视觉方案.md》§2.2）。
 *
 * ⚠️ M0 取舍说明：
 * MIUIX 的视觉标志是**超椭圆圆角（squircle）**而非普通圆弧圆角。真正的 squircle
 * 需要 `miuix-squircle` 的 SquircleShape（底层基于 androidx.graphics.shapes）。
 * M0 阶段的目标是"跑通技术栈 + 验证玻璃稳定性"，因此此处先用 RoundedCornerShape
 * 保证编译与运行，M1 视觉精修时再替换为 SquircleShape。
 *
 * 替换点集中在此文件，业务代码只引用 AppShapes.xxx，届时无需改动。
 */
object AppShapes {

    /** 大卡片（场次卡片、统计卡片） */
    val Card = RoundedCornerShape(26.dp)

    /** 中卡片 / 快捷按钮 */
    val CardSmall = RoundedCornerShape(19.dp)

    /** 底部浮动导航栏（液态玻璃折射的主舞台之一） */
    val Dock = RoundedCornerShape(26.dp)

    /** 玻璃面板 / 底部弹层 */
    val Sheet = RoundedCornerShape(24.dp)

    /** 主按钮 */
    val Button = RoundedCornerShape(22.dp)

    /** 标签 / 芯片 */
    val Chip = RoundedCornerShape(100.dp)

    /** 小图标底座 */
    val IconHolder = RoundedCornerShape(19.dp)

    /** 逐球序号方块 */
    val IndexBadge = RoundedCornerShape(9.dp)
}
