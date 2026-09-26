package com.retropro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.retropro.data.model.DashboardCard
import com.retropro.data.model.Game
import com.retropro.data.model.Match
import com.retropro.data.model.Opponent
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Racket
import com.retropro.data.model.Rally
import com.retropro.data.model.Reminder
import com.retropro.data.model.Session
import com.retropro.data.model.Shuttle
import com.retropro.data.model.ShuttleUsage
import com.retropro.data.model.StringJob

/**
 * 备份专用的 DAO。
 *
 * 单独建一个 DAO 而不是往各业务 DAO 里塞，是因为备份需要的读写模式
 * 与业务完全不同：全表读写、按固定顺序清空/重建，且**不允许**带业务过滤。
 */
@Dao
interface BackupDao {

    // ---------------------------------------------------------------- 读

    @Query("SELECT * FROM sessions ORDER BY id") suspend fun allSessions(): List<Session>
    @Query("SELECT * FROM opponents ORDER BY id") suspend fun allOpponents(): List<Opponent>
    @Query("SELECT * FROM rackets ORDER BY id") suspend fun allRackets(): List<Racket>
    @Query("SELECT * FROM shuttles ORDER BY id") suspend fun allShuttles(): List<Shuttle>
    @Query("SELECT * FROM matches ORDER BY id") suspend fun allMatches(): List<Match>
    @Query("SELECT * FROM games ORDER BY id") suspend fun allGames(): List<Game>
    @Query("SELECT * FROM rallies ORDER BY id") suspend fun allRallies(): List<Rally>
    @Query("SELECT * FROM string_jobs ORDER BY id") suspend fun allStringJobs(): List<StringJob>
    @Query("SELECT * FROM shuttle_usages ORDER BY id") suspend fun allShuttleUsages(): List<ShuttleUsage>
    @Query("SELECT * FROM reminders ORDER BY id") suspend fun allReminders(): List<Reminder>
    @Query("SELECT * FROM dashboard_cards ORDER BY id") suspend fun allDashboardCards(): List<DashboardCard>

    // ---------------------------------------------------------------- 清空

    // 顺序很重要：先删子表再删父表，否则外键会拒绝
    @Query("DELETE FROM rallies") suspend fun clearRallies()
    @Query("DELETE FROM games") suspend fun clearGames()
    @Query("DELETE FROM matches") suspend fun clearMatches()
    @Query("DELETE FROM string_jobs") suspend fun clearStringJobs()
    @Query("DELETE FROM shuttle_usages") suspend fun clearShuttleUsages()
    @Query("DELETE FROM sessions") suspend fun clearSessions()
    @Query("DELETE FROM opponents") suspend fun clearOpponents()
    @Query("DELETE FROM rackets") suspend fun clearRackets()
    @Query("DELETE FROM shuttles") suspend fun clearShuttles()

    @Query("SELECT * FROM gear_items ORDER BY id") suspend fun allGearItems(): List<GearItem>
    @Insert suspend fun insertGearItems(v: List<GearItem>)
    @Query("DELETE FROM gear_items") suspend fun clearGearItems()

    @Query("SELECT * FROM gear_tags ORDER BY id") suspend fun allGearTags(): List<GearTag>
    @Insert suspend fun insertGearTags(v: List<GearTag>)
    @Query("DELETE FROM gear_tags") suspend fun clearGearTags()
    @Query("DELETE FROM reminders") suspend fun clearReminders()
    @Query("DELETE FROM dashboard_cards") suspend fun clearDashboardCards()

    // ---------------------------------------------------------------- 写

    // Room 的 autoGenerate 只在 id == 0 时才生成；
    // 显式带上 id 插入即可保留原有主键，从而维持外键关系完整。
    @Insert suspend fun insertSessions(v: List<Session>)
    @Insert suspend fun insertOpponents(v: List<Opponent>)
    @Insert suspend fun insertRackets(v: List<Racket>)
    @Insert suspend fun insertShuttles(v: List<Shuttle>)
    @Insert suspend fun insertMatches(v: List<Match>)
    @Insert suspend fun insertGames(v: List<Game>)
    @Insert suspend fun insertRallies(v: List<Rally>)
    @Insert suspend fun insertStringJobs(v: List<StringJob>)
    @Insert suspend fun insertShuttleUsages(v: List<ShuttleUsage>)
    @Insert suspend fun insertReminders(v: List<Reminder>)
    @Insert suspend fun insertDashboardCards(v: List<DashboardCard>)
}
