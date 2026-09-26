package com.retropro.di

import android.content.Context
import com.retropro.data.db.AppDatabase
import com.retropro.data.backup.BackupRepository
import com.retropro.data.reminder.ReminderChecker
import com.retropro.data.repository.DiaryRepository
import com.retropro.data.repository.StatsRepository
import com.retropro.data.repository.EquipmentRepository

/**
 * 极简服务定位器 —— 个人自用项目，不引入 Hilt/Koin。
 *
 * 依赖关系一共就三层（DB → Repository → ViewModel），
 * 用 DI 框架带来的构建开销与学习成本都不划算。
 *
 * 所有实例都是**懒加载的进程单例**：DB 只在第一次真正用到时才打开，
 * 应用冷启动不会因为初始化数据库而变慢。
 */
object AppGraph {

    @Volatile
    private var dbRef: AppDatabase? = null

    fun init(context: Context) {
        // 只保存 applicationContext，避免持有 Activity 造成泄漏
        appContext = context.applicationContext
    }

    private lateinit var appContext: Context

    val db: AppDatabase
        get() = dbRef ?: synchronized(this) {
            dbRef ?: AppDatabase.get(appContext).also { dbRef = it }
        }

    val diary: DiaryRepository by lazy { DiaryRepository(db) }

    val equipment: EquipmentRepository by lazy { EquipmentRepository(db) }

    val stats: StatsRepository by lazy { StatsRepository(db) }
    val reminder: ReminderChecker by lazy { ReminderChecker(db) }
    val backup: BackupRepository by lazy { BackupRepository(db, appContext) }
}
