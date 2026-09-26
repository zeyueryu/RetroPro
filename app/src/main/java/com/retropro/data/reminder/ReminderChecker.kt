package com.retropro.data.reminder

import com.retropro.data.db.AppDatabase
import com.retropro.data.model.Reminder
import com.retropro.data.model.ReminderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * 一条提醒的当前状态。
 *
 * [due] 只回答"现在该不该提醒"这一个布尔问题 ——
 * 是否真的提醒，还取决于 [enabled]（用户可以关掉）。
 */
data class ReminderStatus(
    val type: ReminderType,
    val label: String,
    val enabled: Boolean,
    val thresholdDays: Int,
    /** 相关事件最近一次发生的时间；null 表示从未发生过 */
    val lastDate: Long?,
    /** 距今天数；null 同上 */
    val daysSince: Long?,
    val due: Boolean,
    /** 给 UI 直接展示的一句话 */
    val detail: String,
)

/**
 * 提醒的计算逻辑。
 *
 * ## 为什么先做成 App 内提醒，而不是系统通知
 *
 * 系统通知需要 `POST_NOTIFICATIONS`（API 33+ 运行时权限）加上
 * WorkManager / AlarmManager 的调度，而提醒的**判断逻辑本身**很简单：
 * 距上次事件是否超过阈值。先把判断做对、在 App 里展示出来，
 * 真正需要"没打开 App 也要提醒"时再加调度层 —— 那时判断逻辑可以直接复用。
 */
class ReminderChecker(private val db: AppDatabase) {

    private val reminderDao = db.reminderDao()

    /** 首次启动时写入默认配置（穿线 180 天 / 久未打球 30 天） */
    suspend fun seedIfEmpty() {
        if (reminderDao.count() > 0) return
        reminderDao.insert(Reminder(type = ReminderType.STRING_JOB, thresholdDays = 180))
        reminderDao.insert(Reminder(type = ReminderType.INACTIVE, thresholdDays = 30))
    }

    suspend fun all(): List<Reminder> = reminderDao.all()

    suspend fun setEnabled(type: ReminderType, enabled: Boolean) = withContext(Dispatchers.IO) {
        reminderDao.byType(type)?.let { reminderDao.update(it.copy(enabled = enabled)) }
    }

    suspend fun setThreshold(type: ReminderType, days: Int) = withContext(Dispatchers.IO) {
        reminderDao.byType(type)?.let { reminderDao.update(it.copy(thresholdDays = days)) }
    }

    /**
     * 计算所有提醒的当前状态。
     *
     * 「久未打球」的口径是**所有场次里最晚的一场** —— 用的是你实际去打球的日子，
     * 不是你打开 App 的日子。
     */
    suspend fun statuses(): List<ReminderStatus> = withContext(Dispatchers.IO) {
        val configs = reminderDao.all()
        if (configs.isEmpty()) return@withContext emptyList()

        val lastPlay = reminderDao.lastPlayDate()
        val lastString = reminderDao.lastStringDateForActive()
        val now = System.currentTimeMillis()

        configs.map { config ->
            when (config.type) {
                ReminderType.STRING_JOB -> {
                    val days = lastString?.let { (now - it) / DAY_MS }
                    ReminderStatus(
                        type = config.type,
                        label = config.type.label,
                        enabled = config.enabled,
                        thresholdDays = config.thresholdDays,
                        lastDate = lastString,
                        daysSince = days,
                        due = config.enabled && days != null && days >= config.thresholdDays,
                        detail = when {
                            days == null -> "还没有穿线记录"
                            else -> "上次穿线 " + (days.toString() + " 天前")
                        },
                    )
                }

                ReminderType.INACTIVE -> {
                    val days = lastPlay?.let { (now - it) / DAY_MS }
                    ReminderStatus(
                        type = config.type,
                        label = config.type.label,
                        enabled = config.enabled,
                        thresholdDays = config.thresholdDays,
                        lastDate = lastPlay,
                        daysSince = days,
                        due = config.enabled && days != null && days >= config.thresholdDays,
                        detail = when {
                            days == null -> "还没有打球记录"
                            else -> "上次打球 " + (days.toString() + " 天前")
                        },
                    )
                }
            }
        }
    }
}
