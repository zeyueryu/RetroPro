package com.retropro.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日期与数字格式化。
 *
 * 全部走 `java.time`（minSdk 33 起原生可用，无需 desugaring）。
 * 刻意不做"智能相对时间"（几分钟前/昨天）—— 这个 App 的日期是**训练日志**，
 * 用户需要的是确切日期，而不是"3 天前"。
 */
object Formatters {

    private val dateFmt = DateTimeFormatter.ofPattern("M月d日")
    private val dateWithWeekFmt = DateTimeFormatter.ofPattern("M月d日 EEEE")
    private val dateWithYearFmt = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    private fun toLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 列表用：今年省略年份，往年带上 */
    fun sessionDate(epochMillis: Long): String {
        val date = toLocalDate(epochMillis)
        val today = LocalDate.now()
        return if (date.year == today.year) date.format(dateFmt) else date.format(dateWithYearFmt)
    }

    /** 详情页用：带星期，方便回忆"那天是周几" */
    fun fullDate(epochMillis: Long): String = toLocalDate(epochMillis).format(dateWithWeekFmt)

    fun time(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)

    /** 时长：90 → "1 小时 30 分"，60 → "1 小时" */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "—"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    /** 金额：整数不带小数，避免"¥120.0"这种碍眼的写法 */
    fun money(yuan: Double): String =
        if (yuan <= 0.0) "—"
        else if (yuan % 1.0 == 0.0) "¥${yuan.toInt()}"
        else "¥%.2f".format(yuan)

    /** 数量：0.5 / 1 / 1.5 这种，最多一位小数 */
    fun count(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    /** 距今天数 → 人话。用于装备页"上次穿线" */
    fun daysAgo(days: Long): String = when {
        days <= 0 -> "今天"
        days == 1L -> "昨天"
        days < 30 -> "$days 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}
