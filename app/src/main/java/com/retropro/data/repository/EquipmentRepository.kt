package com.retropro.data.repository

import androidx.room.withTransaction
import com.retropro.data.db.AppDatabase
import com.retropro.data.db.OpponentWithCount
import com.retropro.data.db.RacketWithLastString
import com.retropro.data.db.ShuttleUsageDetail
import com.retropro.data.model.Opponent
import com.retropro.data.model.Racket
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Shuttle
import com.retropro.data.model.ShuttleUsage
import com.retropro.data.model.StringJob
import kotlinx.coroutines.flow.Flow

/**
 * 装备与对手的仓储层。
 *
 * 与 [DiaryRepository] 分开，是因为这一侧的写操作**没有跨表不变式**
 * （穿线记录与用球明细都是独立追加的），不需要统一的事务纪律。
 *
 * 设计取舍（用户明确要求）：穿线**只记日期 + 价格**，外加线型号与磅数，
 * 不做寿命衰减推算。所以这里没有任何"剩余寿命 / 已用小时"的逻辑。
 */
class EquipmentRepository(private val db: AppDatabase) {

    private val racketDao = db.racketDao()
    private val stringJobDao = db.stringJobDao()
    private val stringJobListDao = db.stringJobListDao()
    private val shuttleDao = db.shuttleDao()
    private val opponentDao = db.opponentDao()
    private val gearItemDao = db.gearItemDao()
    private val gearTagDao = db.gearTagDao()

    // ---------------------------------------------------------------- 球拍 / 穿线

    fun observeRackets(): Flow<List<RacketWithLastString>> = racketDao.observeWithLastString()

    fun observeAllStringJobs() = stringJobListDao.observeAll()

    suspend fun activeRackets(): List<Racket> = racketDao.active()

    suspend fun racketById(id: Long): Racket? = racketDao.byId(id)

    suspend fun addRacket(brand: String, model: String, note: String? = null): Long =
        racketDao.insert(
            Racket(
                brand = brand.trim(),
                model = model.trim(),
                note = note?.takeIf { it.isNotBlank() },
            ),
        )

    suspend fun updateRacket(racket: Racket) = racketDao.update(racket)

    /** 退役而不是删除 —— 历史穿线记录要留着 */
    suspend fun retireRacket(racket: Racket) = racketDao.update(racket.copy(isActive = false))

    fun observeStringJobs(racketId: Long): Flow<List<StringJob>> =
        stringJobDao.observeByRacket(racketId)

    suspend fun addStringJob(
        racketId: Long,
        date: Long,
        lineModel: String,
        tensionLbs: Int,
        priceYuan: Double,
        note: String? = null,
    ): Long = stringJobDao.insert(
        StringJob(
            racketId = racketId,
            date = date,
            lineModel = lineModel.trim(),
            tensionLbs = tensionLbs,
            priceYuan = priceYuan,
            note = note?.takeIf { it.isNotBlank() },
        ),
    )

    suspend fun deleteStringJob(job: StringJob) = stringJobDao.delete(job)

    /** 距上次穿线过了多少天。用于「该换线了」提醒，但不做任何预测 */
    suspend fun daysSinceLastString(racketId: Long): Long? =
        stringJobDao.lastDate(racketId)?.let { (System.currentTimeMillis() - it) / DAY_MS }

    // ---------------------------------------------------------------- 用球

    fun observeShuttles(): Flow<List<Shuttle>> = shuttleDao.observeAll()

    suspend fun addShuttle(brand: String, model: String, note: String? = null): Long =
        shuttleDao.insert(Shuttle(brand = brand.trim(), model = model.trim(), note = note))

    suspend fun addShuttleUsage(
        sessionId: Long,
        shuttleId: Long,
        count: Double,
        unitCost: Double,
    ): Long = shuttleDao.insertUsage(
        ShuttleUsage(
            sessionId = sessionId,
            shuttleId = shuttleId,
            count = count,
            unitCost = unitCost,
        ),
    )

    suspend fun deleteShuttleUsage(usage: ShuttleUsage) = shuttleDao.deleteUsage(usage)

    /**
     * 删除球型号。
     *
     * `shuttle_usages.shuttleId` 上是 `ForeignKey.CASCADE` —— 删型号会连带删掉
     * 它在各场次里的用球明细，统计页的用球花费因此会变小。调用方必须二次确认并说明这点。
     */
    suspend fun deleteShuttle(shuttle: Shuttle) = shuttleDao.delete(shuttle)

    suspend fun shuttleById(id: Long): Shuttle? = shuttleDao.byId(id)

    /**
     * 删除球拍。
     *
     * `string_jobs.racketId` 上是 `ForeignKey.CASCADE` —— 删拍会连带删掉全部穿线历史。
     * 日常「不用了」应该走 [retireRacket]（保留历史），删除只用于录错的拍。
     */
    suspend fun deleteRacket(racket: Racket) = racketDao.delete(racket)

    fun observeSessionShuttleUsage(sessionId: Long): Flow<List<ShuttleUsageDetail>> =
        shuttleDao.observeUsageOfSession(sessionId)

    fun observeTotalShuttleCost(): Flow<Double> = shuttleDao.observeTotalShuttleCost()

    // ---------------------------------------------------------------- 对手

    fun observeOpponents(): Flow<List<OpponentWithCount>> = opponentDao.observeWithCount()

    suspend fun allOpponents(): List<Opponent> = opponentDao.all()

    suspend fun opponent(id: Long): Opponent? = opponentDao.byId(id)

    suspend fun upsertOpponent(
        id: Long?,
        name: String,
        styleTags: List<String>,
        levelNote: String?,
        note: String?,
    ): Long = db.withTransaction {
        val tags = styleTags.joinToString(", ")
        val trimmedNote = note?.takeIf { it.isNotBlank() }
        val trimmedLevel = levelNote?.takeIf { it.isNotBlank() }
        if (id == null || id == 0L) {
            opponentDao.insert(
                Opponent(
                    name = name.trim(),
                    styleTags = tags,
                    levelNote = trimmedLevel,
                    note = trimmedNote,
                ),
            )
        } else {
            opponentDao.byId(id)?.let { existing ->
                opponentDao.update(
                    existing.copy(
                        name = name.trim(),
                        styleTags = tags,
                        levelNote = trimmedLevel,
                        note = trimmedNote,
                    ),
                )
            }
            id
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    // ---------------------------------------------------------------- 其他装备（球鞋/球衣/手胶）

    fun observeGear(category: String) = gearItemDao.observeByCategory(category)

    fun observeGearTags() = gearTagDao.observeAll()

    fun observeGearCosts() = gearItemDao.observeCategoryCosts()

    suspend fun addGear(category: String, name: String, brand: String, priceYuan: Double, boughtDate: Long) {
        gearItemDao.insert(
            GearItem(
                category = category,
                name = name.trim(),
                brand = brand.trim(),
                priceYuan = priceYuan,
                boughtDate = boughtDate,
            ),
        )
    }

    suspend fun deleteGear(item: GearItem) = gearItemDao.delete(item)

    /** 按 id 取完整装备实体（删除前必须先取，见 [GearItemDao.byId] 的说明） */
    suspend fun gearItemById(id: Long): GearItem? = gearItemDao.byId(id)

    /** 按 id 取品牌标签实体 */
    suspend fun gearTagById(id: Long): GearTag? = gearTagDao.byId(id)

    /** 清空某分类的全部装备条目。`gear_items` 没有子表，不需要事务。 */
    suspend fun clearGear(category: String) = gearItemDao.deleteByCategory(category)

    suspend fun setGearActive(item: GearItem, active: Boolean) =
        gearItemDao.setActive(item.id, active)

    suspend fun addGearTag(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        // 重名幂等：唯一索引 + 忽略结果
        gearTagDao.insert(GearTag(name = n))
        return true
    }

    suspend fun deleteGearTag(tag: GearTag) = gearTagDao.delete(tag.id)

    /** 首次使用时确保 16 个预设品牌存在（Migration 之外的第二道保险，老库升级也覆盖） */
    suspend fun seedDefaultTagsIfEmpty() {
        if (gearTagDao.count() == 0) {
            listOf(
                "尤尼克斯", "胜利", "李宁", "威臣", "川崎", "蟹羽", "华羽", "熏风",
                "华美", "凯胜", "亚瑟士", "亚狮龙", "高神", "波力", "安踏", "欧击",
            ).forEach { gearTagDao.insert(GearTag(name = it)) }
        }
    }
}
