package com.retropro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.retropro.data.model.DashboardCard
import com.retropro.data.model.Game
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Match
import com.retropro.data.model.Opponent
import com.retropro.data.model.Racket
import com.retropro.data.model.Rally
import com.retropro.data.model.Reminder
import com.retropro.data.model.Session
import com.retropro.data.model.Shuttle
import com.retropro.data.model.ShuttleUsage
import com.retropro.data.model.StringJob

/**
 * 本地数据库 —— 11 张表，全部离线，对应设计方案 §5.2。
 *
 * ```
 * sessions ─┬─ matches ─┬─ games ─── rallies
 *           │           └─ opponents
 *           └─ shuttle_usages ── shuttles
 * rackets ─── string_jobs
 * reminders / dashboard_cards（配置表）
 * ```
 *
 * ## 版本策略
 *
 * `version = 1` 起步。**每次改表结构都要 bump version 并写 Migration** ——
 * 导出到 `app/schemas` 的 JSON 是写迁移时的对照依据（已在 build.gradle.kts 配置）。
 * 个人自用 App 里数据是真实积累的，不允许用 `fallbackToDestructiveMigration` 抹掉。
 */
@Database(
    entities = [
        Session::class,
        Match::class,
        Game::class,
        Rally::class,
        Opponent::class,
        Racket::class,
        StringJob::class,
        Shuttle::class,
        ShuttleUsage::class,
        Reminder::class,
        DashboardCard::class,
        GearItem::class,
        GearTag::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun matchDao(): MatchDao
    abstract fun gameDao(): GameDao
    abstract fun rallyDao(): RallyDao
    abstract fun racketDao(): RacketDao
    abstract fun stringJobDao(): StringJobDao
    abstract fun stringJobListDao(): StringJobListDao
    abstract fun shuttleDao(): ShuttleDao
    abstract fun opponentDao(): OpponentDao
    abstract fun reminderDao(): ReminderDao
    abstract fun dashboardCardDao(): DashboardCardDao
    abstract fun backupDao(): BackupDao
    abstract fun gearItemDao(): GearItemDao
    abstract fun gearTagDao(): GearTagDao

    companion object {

        private const val NAME = "retropro.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** v1 → v2：新增 gear_items（球鞋/球衣/手胶）与 gear_tags（品牌标签，预置 16 品牌） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gear_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        category TEXT NOT NULL,
                        name TEXT NOT NULL,
                        brand TEXT NOT NULL,
                        priceYuan REAL NOT NULL,
                        boughtDate INTEGER NOT NULL,
                        note TEXT,
                        isActive INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gear_tags (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gear_tags_name ON gear_tags(name)")
                // 预设羽毛球品牌标签（用户指定的 16 个）
                val brands = listOf(
                    "尤尼克斯", "胜利", "李宁", "威臣", "川崎", "蟹羽", "华羽", "熏风",
                    "华美", "凯胜", "亚瑟士", "亚狮龙", "高神", "波力", "安踏", "欧击",
                )
                for (b in brands) {
                    db.execSQL("INSERT OR IGNORE INTO gear_tags(name) VALUES ('$b')")
                }
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // WAL：读写并发更好，单条写入不会阻塞读取（计分板高频写分正好用得上）
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
