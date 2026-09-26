package com.retropro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Racket
import com.retropro.data.model.Shuttle
import com.retropro.data.model.ShuttleUsage
import com.retropro.data.model.StringJob
import kotlinx.coroutines.flow.Flow

/** 球拍 + 它最近一次穿线 —— 装备页列表项 */
data class RacketWithLastString(
    val id: Long,
    val brand: String,
    val model: String,
    val isActive: Boolean,
    val lastStringDate: Long?,
    val lastStringModel: String?,
    val lastStringTension: Int?,
    val lastStringPrice: Double?,
    val stringJobCount: Int,
)

/** 某场次的用球消耗明细 */
data class ShuttleUsageDetail(
    val id: Long,
    val brand: String,
    val model: String,
    val count: Double,
    val unitCost: Double,
)

@Dao
interface RacketDao {

    @Insert
    suspend fun insert(racket: Racket): Long

    @Update
    suspend fun update(racket: Racket)

    @Delete
    suspend fun delete(racket: Racket)

    @Query("SELECT * FROM rackets ORDER BY isActive DESC, id")
    fun observeAll(): Flow<List<Racket>>

    @Query("SELECT * FROM rackets WHERE isActive = 1 ORDER BY id")
    suspend fun active(): List<Racket>

    /**
     * 按主键取完整实体。
     *
     * [observeWithLastString] 返回的是查询投影（只为列表展示服务，不含 note 等字段），
     * 要写回时必须先拿到完整实体，否则 update 会把缺失字段覆盖成默认值。
     */
    @Query("SELECT * FROM rackets WHERE id = :id")
    suspend fun byId(id: Long): Racket?

    /**
     * 球拍 + 最近一次穿线。
     *
     * 用相关子查询而非 join + group by：一支拍的穿线记录很少（几条到几十条），
     * 子查询更直观，也省掉"取每组最新一行"那种容易写错的取巧写法。
     */
    @Query(
        """
        SELECT
            r.id AS id,
            r.brand AS brand,
            r.model AS model,
            r.isActive AS isActive,
            (SELECT sj.date FROM string_jobs sj WHERE sj.racketId = r.id ORDER BY sj.date DESC LIMIT 1) AS lastStringDate,
            (SELECT sj.lineModel FROM string_jobs sj WHERE sj.racketId = r.id ORDER BY sj.date DESC LIMIT 1) AS lastStringModel,
            (SELECT sj.tensionLbs FROM string_jobs sj WHERE sj.racketId = r.id ORDER BY sj.date DESC LIMIT 1) AS lastStringTension,
            (SELECT sj.priceYuan FROM string_jobs sj WHERE sj.racketId = r.id ORDER BY sj.date DESC LIMIT 1) AS lastStringPrice,
            (SELECT COUNT(*) FROM string_jobs sj WHERE sj.racketId = r.id) AS stringJobCount
        FROM rackets r
        ORDER BY r.isActive DESC, r.id
        """
    )
    fun observeWithLastString(): Flow<List<RacketWithLastString>>
}

@Dao
interface StringJobDao {

    @Insert
    suspend fun insert(job: StringJob): Long

    @Update
    suspend fun update(job: StringJob)

    @Delete
    suspend fun delete(job: StringJob)

    /** 穿线历史分层：同一支拍按日期倒序 */
    @Query("SELECT * FROM string_jobs WHERE racketId = :racketId ORDER BY date DESC")
    fun observeByRacket(racketId: Long): Flow<List<StringJob>>

    /** 最近一次穿线的日期，用于"该换线了"提醒 */
    @Query("SELECT MAX(date) FROM string_jobs WHERE racketId = :racketId")
    suspend fun lastDate(racketId: Long): Long?
}

@Dao
interface ShuttleDao {

    @Insert
    suspend fun insert(shuttle: Shuttle): Long

    @Update
    suspend fun update(shuttle: Shuttle)

    @Delete
    suspend fun delete(shuttle: Shuttle)

    @Query("SELECT * FROM shuttles ORDER BY id")
    fun observeAll(): Flow<List<Shuttle>>

    /** 按主键取完整实体 —— 列表项是投影时，删除/写回前先取实体 */
    @Query("SELECT * FROM shuttles WHERE id = :id")
    suspend fun byId(id: Long): Shuttle?

    @Insert
    suspend fun insertUsage(usage: ShuttleUsage): Long

    @Delete
    suspend fun deleteUsage(usage: ShuttleUsage)

    @Query(
        """
        SELECT u.id AS id, s.brand AS brand, s.model AS model,
               u.count AS count, u.unitCost AS unitCost
        FROM shuttle_usages u
        JOIN shuttles s ON u.shuttleId = s.id
        WHERE u.sessionId = :sessionId
        ORDER BY u.id
        """
    )
    fun observeUsageOfSession(sessionId: Long): Flow<List<ShuttleUsageDetail>>

    /** 全部用球花费（统计页用） */
    @Query("SELECT IFNULL(SUM(count * unitCost), 0.0) FROM shuttle_usages")
    fun observeTotalShuttleCost(): Flow<Double>
}


// ---------------------------------------------------------------- 其他装备（球鞋/球衣/手胶）

/** 按分类的装备花费汇总（统计页用） */
data class CategoryCost(
    val category: String,
    val total: Double,
    val count: Int,
)

/** 穿线记录行（带球拍名）—— 球线管理卡用 */
data class StringJobRow(
    val id: Long,
    val date: Long,
    val lineModel: String,
    val tensionLbs: Int,
    val priceYuan: Double,
    val racketName: String,
)

@Dao
interface StringJobListDao {
    @Query(
        """
        SELECT sj.id, sj.date, sj.lineModel, sj.tensionLbs, sj.priceYuan,
               TRIM(r.brand || ' ' || r.model) AS racketName
        FROM string_jobs sj JOIN rackets r ON r.id = sj.racketId
        ORDER BY sj.date DESC, sj.id DESC
        """
    )
    fun observeAll(): Flow<List<StringJobRow>>
}

@Dao
interface GearItemDao {

    @Insert
    suspend fun insert(item: GearItem): Long

    @Delete
    suspend fun delete(item: GearItem)

    @Query("UPDATE gear_items SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)

    @Query("SELECT * FROM gear_items WHERE category = :category ORDER BY isActive DESC, boughtDate DESC, id DESC")
    fun observeByCategory(category: String): Flow<List<GearItem>>

    /**
     * 按 id 取完整实体。
     *
     * 删除/写回前必须先取实体，不能拿列表项直接 `@Delete` ——
     * 列表可能来自 `@Query` 投影（只含展示列），
     * 拿它删/存会把投影里不存在的列覆盖成默认值。
     */
    @Query("SELECT * FROM gear_items WHERE id = :id")
    suspend fun byId(id: Long): GearItem?

    @Query("SELECT * FROM gear_items ORDER BY id")
    suspend fun all(): List<GearItem>

    /** 清空某个分类的全部条目（长按汇总卡「清空记录」用） */
    @Query("DELETE FROM gear_items WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    /** 各分类合计花费（统计页） */
    @Query(
        """
        SELECT category, SUM(priceYuan) AS total, COUNT(*) AS count
        FROM gear_items WHERE isActive = 1
        GROUP BY category
        """
    )
    fun observeCategoryCosts(): Flow<List<CategoryCost>>
}

@Dao
interface GearTagDao {

    @Insert
    suspend fun insert(tag: GearTag): Long

    @Query("DELETE FROM gear_tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM gear_tags ORDER BY id")
    fun observeAll(): Flow<List<GearTag>>

    @Query("SELECT COUNT(*) FROM gear_tags")
    suspend fun count(): Int

    @Query("SELECT * FROM gear_tags ORDER BY id")
    suspend fun all(): List<GearTag>

    /** 按 id 取实体（确认框只存了 id，执行删除前要取回来交给 `@Delete`） */
    @Query("SELECT * FROM gear_tags WHERE id = :id")
    suspend fun byId(id: Long): GearTag?
}
