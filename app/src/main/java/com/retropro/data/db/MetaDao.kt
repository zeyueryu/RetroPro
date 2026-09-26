package com.retropro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.retropro.data.model.DashboardCard
import com.retropro.data.model.Opponent
import com.retropro.data.model.Reminder
import com.retropro.data.model.ReminderType
import kotlinx.coroutines.flow.Flow

/** 对手 + 交手次数（对手列表项） */
data class OpponentWithCount(
    val id: Long,
    val name: String,
    val styleTags: String,
    val levelNote: String?,
    val note: String?,
    val matchCount: Int,
) {
    val styleTagList: List<String>
        get() = styleTags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Dao
interface OpponentDao {

    @Insert
    suspend fun insert(opponent: Opponent): Long

    @Update
    suspend fun update(opponent: Opponent)

    @Delete
    suspend fun delete(opponent: Opponent)

    @Query("SELECT * FROM opponents WHERE id = :id")
    suspend fun byId(id: Long): Opponent?

    @Query("SELECT * FROM opponents ORDER BY name")
    suspend fun all(): List<Opponent>

    /** 对手列表 + 交手次数，按交手次数倒序 —— 常打的人排前面 */
    @Query(
        """
        SELECT
            o.id AS id,
            o.name AS name,
            o.styleTags AS styleTags,
            o.levelNote AS levelNote,
            o.note AS note,
            (SELECT COUNT(*) FROM matches m WHERE m.opponentId = o.id) AS matchCount
        FROM opponents o
        ORDER BY matchCount DESC, o.name
        """
    )
    fun observeWithCount(): Flow<List<OpponentWithCount>>

    /** 按名字精确匹配（新建场次时复用已有对手，避免重复录入同名） */
    @Query("SELECT * FROM opponents WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Opponent?
}

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE type = :type LIMIT 1")
    suspend fun byType(type: ReminderType): Reminder?

    /** 首次启动时写入默认提醒，保证设置页一定有可编辑的行 */
    @Query("SELECT COUNT(*) FROM reminders")
    suspend fun count(): Int

    /** 最近一次打球的时间（所有场次里最晚的）。用于「久未打球」提醒 */
    @Query("SELECT MAX(date) FROM sessions")
    suspend fun lastPlayDate(): Long?

    /**
     * 在用球拍里最近一次穿线的时间。
     *
     * 只看 `isActive = 1`：退役球拍的历史穿线不应该触发「该换线了」。
     */
    @Query(
        """
        SELECT MAX(sj.date) FROM string_jobs sj
        JOIN rackets r ON sj.racketId = r.id
        WHERE r.isActive = 1
        """
    )
    suspend fun lastStringDateForActive(): Long?
}

@Dao
interface DashboardCardDao {

    @Insert
    suspend fun insert(card: DashboardCard): Long

    @Update
    suspend fun update(card: DashboardCard)

    @Delete
    suspend fun delete(card: DashboardCard)

    @Query("SELECT * FROM dashboard_cards ORDER BY orderIndex")
    fun observeAll(): Flow<List<DashboardCard>>

    /** 一次性取全部（换位、追加时用） */
    @Query("SELECT * FROM dashboard_cards ORDER BY orderIndex")
    suspend fun allOrdered(): List<DashboardCard>

    @Insert
    suspend fun insertDashboardCards(v: List<DashboardCard>)

    @Query("SELECT COUNT(*) FROM dashboard_cards")
    suspend fun count(): Int

    /** 清空全部指标卡（「重置为默认」用；调用方必须包在事务里） */
    @Query("DELETE FROM dashboard_cards")
    suspend fun deleteAll()
}
