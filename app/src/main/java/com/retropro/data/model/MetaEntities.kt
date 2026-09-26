package com.retropro.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对手 + 两个配置表。
 *
 * 对应《羽毛球日记-App-设计方案.md》§5.2。
 */

/** 提醒类型。文档：0=穿线 1=久未打球 */
enum class ReminderType(val code: Int, val label: String) {
    STRING_JOB(0, "该换线了"),
    INACTIVE(1, "好久没打球"),
    ;

    companion object {
        fun of(code: Int): ReminderType = entries.firstOrNull { it.code == code } ?: STRING_JOB
    }
}

/**
 * opponents —— 对手。
 *
 * "整理出对战次数"通过 `matches.opponentId` 聚合得到，不在此表冗余计数。
 * 风格标签用逗号分隔的字符串存储（拉吊型/力量型/速度型/网前型/控制型）——
 * 标签集合固定且量少，单独建表会让查询和录入都变复杂，收益不足。
 */
@Entity(tableName = "opponents")
data class Opponent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** 风格标签，逗号分隔 */
    val styleTags: String = "",
    /** 水平描述 */
    val levelNote: String? = null,
    /** 自由备注 */
    val note: String? = null,
) {
    /** 拆分后的风格标签，便于 UI 直接渲染成 chip */
    val styleTagList: List<String>
        get() = styleTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** reminders —— 提醒配置 */
@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ReminderType = ReminderType.STRING_JOB,
    /** 阈值天数 */
    val thresholdDays: Int = 180,
    val enabled: Boolean = true,
)

/**
 * dashboard_cards —— 自定义统计卡片（预留）。
 *
 * 用户在需求里明确要"自定义空间预留"，所以这里先把表建好、UI 留槽位，
 * 具体指标在统计页落地时再填充 [metricKey] 的取值集合。
 */
@Entity(tableName = "dashboard_cards")
data class DashboardCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 指标标识，如 "win_games" / "reviewed_games" */
    val metricKey: String,
    val orderIndex: Int = 0,
    val visible: Boolean = true,
)
