package com.retropro.data.backup

import androidx.room.withTransaction
import com.retropro.data.db.AppDatabase
import com.retropro.data.db.BackupDao
import com.retropro.data.model.DashboardCard
import com.retropro.data.model.Game
import com.retropro.data.model.Match
import com.retropro.data.model.MatchMode
import com.retropro.data.model.MatchResult
import com.retropro.data.model.Opponent
import com.retropro.data.model.Racket
import com.retropro.data.model.Rally
import com.retropro.data.model.Reminder
import com.retropro.data.model.ReminderType
import com.retropro.data.model.Scorer
import com.retropro.data.model.Session
import com.retropro.data.model.SessionType
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Shuttle
import com.retropro.data.model.ShuttleUsage
import com.retropro.data.model.StringJob
import com.retropro.data.model.GameResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份 / 恢复 —— 全库 JSON。
 *
 * ## 为什么用 org.json 而不是 kotlinx-serialization
 *
 * kotlinx-serialization 需要一个额外的 Kotlin 编译器插件，
 * 在 AGP 9 内置 Kotlin 的环境下多引一个编译器插件是没必要冒的险。
 * `org.json` 是 Android 平台自带的，零依赖，且备份的字段全是基本类型，
 * 手写映射非常直白。
 *
 * ## 恢复的顺序与主键
 *
 * 恢复 = **清空后按父表→子表顺序整批重插**。Room 的 autoGenerate
 * 只在 id == 0 时才生成，所以显式带上 id 插入即可保留原主键，
 * 外键关系因此不会断。整个过程在一个事务里：中途失败就整体回滚，
 * 不会留下"清空了但没恢复"的半截状态。
 *
 * ## schema 字段名必须与数据库列名一致
 *
 * 备份 JSON 的键名用的是**数据库列名**（而不是 Kotlin 属性名），
 * 这样将来手工检查备份文件时可以直接和导出的 schema 文件对照。
 */
object BackupManager {

    private const val FORMAT_VERSION = 1

    // 表的插入顺序：父表在前，子表在后（外键依赖）
    private val INSERT_ORDER = listOf(
        "sessions", "opponents", "rackets", "shuttles", "matches",
        "games", "rallies", "string_jobs", "shuttle_usages", "reminders", "dashboard_cards",
        "gear_items", "gear_tags",
    )

    /** 清空顺序：子表在前 */
    private val CLEAR_ORDER = INSERT_ORDER.reversed()

    suspend fun export(db: AppDatabase): JSONObject {
        val dao = db.backupDao()
        val root = JSONObject()
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("sessions", JSONArray().apply { dao.allSessions().forEach { put(sessionToJson(it)) } })
        root.put("opponents", JSONArray().apply { dao.allOpponents().forEach { put(opponentToJson(it)) } })
        root.put("rackets", JSONArray().apply { dao.allRackets().forEach { put(racketToJson(it)) } })
        root.put("shuttles", JSONArray().apply { dao.allShuttles().forEach { put(shuttleToJson(it)) } })
        root.put("gear_items", JSONArray().apply { dao.allGearItems().forEach { put(gearItemToJson(it)) } })
        root.put("gear_tags", JSONArray().apply { dao.allGearTags().forEach { put(gearTagToJson(it)) } })
        root.put("matches", JSONArray().apply { dao.allMatches().forEach { put(matchToJson(it)) } })
        root.put("games", JSONArray().apply { dao.allGames().forEach { put(gameToJson(it)) } })
        root.put("rallies", JSONArray().apply { dao.allRallies().forEach { put(rallyToJson(it)) } })
        root.put("string_jobs", JSONArray().apply { dao.allStringJobs().forEach { put(stringJobToJson(it)) } })
        root.put("shuttle_usages", JSONArray().apply { dao.allShuttleUsages().forEach { put(shuttleUsageToJson(it)) } })
        root.put("reminders", JSONArray().apply { dao.allReminders().forEach { put(reminderToJson(it)) } })
        root.put("dashboard_cards", JSONArray().apply { dao.allDashboardCards().forEach { put(dashboardCardToJson(it)) } })
        return root
    }

    suspend fun restore(db: AppDatabase, root: JSONObject) {
        val dao = db.backupDao()
        val version = root.optInt("formatVersion", 0)
        require(version in 1..FORMAT_VERSION) {
            "备份文件版本不受支持（formatVersion=$version，当前支持 1..$FORMAT_VERSION）"
        }

        db.withTransaction {
            CLEAR_ORDER.forEach { name -> dao.clearByName(name) }

            dao.insertSessions(array(root, "sessions") { sessionFromJson(it) })
            dao.insertOpponents(array(root, "opponents") { opponentFromJson(it) })
            dao.insertRackets(array(root, "rackets") { racketFromJson(it) })
            dao.insertShuttles(array(root, "shuttles") { shuttleFromJson(it) })
            dao.insertGearItems(array(root, "gear_items") { gearItemFromJson(it) })
            dao.insertGearTags(array(root, "gear_tags") { gearTagFromJson(it) })
            dao.insertMatches(array(root, "matches") { matchFromJson(it) })
            dao.insertGames(array(root, "games") { gameFromJson(it) })
            dao.insertRallies(array(root, "rallies") { rallyFromJson(it) })
            dao.insertStringJobs(array(root, "string_jobs") { stringJobFromJson(it) })
            dao.insertShuttleUsages(array(root, "shuttle_usages") { shuttleUsageFromJson(it) })
            dao.insertReminders(array(root, "reminders") { reminderFromJson(it) })
            dao.insertDashboardCards(array(root, "dashboard_cards") { dashboardCardFromJson(it) })
        }
    }

    private fun <T> array(root: JSONObject, name: String, map: (JSONObject) -> T): List<T> {
        val arr = root.optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).map { map(arr.getJSONObject(it)) }
    }

    // ---------------------------------------------------------------- sessions

    private fun sessionToJson(v: Session) = JSONObject()
        .put("id", v.id).put("date", v.date).put("venue", v.venue)
        .put("durationMin", v.durationMin).put("feeYuan", v.feeYuan)
        .put("stamina", v.stamina).put("type", v.type.code)
        .put("note", v.note).put("createdAt", v.createdAt)

    private fun sessionFromJson(o: JSONObject) = Session(
        id = o.getLong("id"), date = o.getLong("date"), venue = o.optString("venue"),
        durationMin = o.optInt("durationMin"), feeYuan = o.optDouble("feeYuan"),
        stamina = o.optInt("stamina"), type = SessionType.of(o.optInt("type", 1)),
        note = o.optString("note").takeIf { it.isNotEmpty() },
        createdAt = o.getLong("createdAt"),
    )

    // ---------------------------------------------------------------- opponents

    private fun opponentToJson(v: Opponent) = JSONObject()
        .put("id", v.id).put("name", v.name).put("styleTags", v.styleTags)
        .put("levelNote", v.levelNote).put("note", v.note)

    private fun opponentFromJson(o: JSONObject) = Opponent(
        id = o.getLong("id"), name = o.optString("name"),
        styleTags = o.optString("styleTags"),
        levelNote = o.optString("levelNote").takeIf { it.isNotEmpty() },
        note = o.optString("note").takeIf { it.isNotEmpty() },
    )

    // ---------------------------------------------------------------- rackets

    private fun racketToJson(v: Racket) = JSONObject()
        .put("id", v.id).put("brand", v.brand).put("model", v.model)
        .put("note", v.note).put("isActive", v.isActive)

    private fun racketFromJson(o: JSONObject) = Racket(
        id = o.getLong("id"), brand = o.optString("brand"), model = o.optString("model"),
        note = o.optString("note").takeIf { it.isNotEmpty() },
        isActive = o.optBoolean("isActive", true),
    )

    // ---------------------------------------------------------------- shuttles

    private fun shuttleToJson(v: Shuttle) = JSONObject()
        .put("id", v.id).put("brand", v.brand).put("model", v.model).put("note", v.note)

    private fun shuttleFromJson(o: JSONObject) = Shuttle(
        id = o.getLong("id"), brand = o.optString("brand"),
        model = o.optString("model"),
        note = o.optString("note").takeIf { it.isNotEmpty() },
    )

    // ---------------------------------------------------------------- 其他装备（球鞋/球衣/手胶）与标签

    private fun gearItemToJson(v: GearItem) = JSONObject()
        .put("id", v.id).put("category", v.category).put("name", v.name)
        .put("brand", v.brand).put("priceYuan", v.priceYuan)
        .put("boughtDate", v.boughtDate).put("note", v.note).put("isActive", v.isActive)

    private fun gearItemFromJson(o: JSONObject) = GearItem(
        id = o.getLong("id"), category = o.getString("category"), name = o.optString("name"),
        brand = o.optString("brand"), priceYuan = o.optDouble("priceYuan", 0.0),
        boughtDate = o.optLong("boughtDate", 0),
        note = o.optString("note").takeIf { it.isNotEmpty() },
        isActive = o.optBoolean("isActive", true),
    )

    private fun gearTagToJson(v: GearTag) = JSONObject()
        .put("id", v.id).put("name", v.name)

    private fun gearTagFromJson(o: JSONObject) = GearTag(
        id = o.getLong("id"), name = o.optString("name"),
    )

    // ---------------------------------------------------------------- matches

    private fun matchToJson(v: Match) = JSONObject()
        .put("id", v.id).put("sessionId", v.sessionId).put("opponentId", v.opponentId)
        .put("mode", v.mode.code).put("myGamesWon", v.myGamesWon)
        .put("oppGamesWon", v.oppGamesWon).put("result", v.result.code).put("note", v.note)

    private fun matchFromJson(o: JSONObject) = Match(
        id = o.getLong("id"), sessionId = o.getLong("sessionId"),
        opponentId = if (o.isNull("opponentId")) null else o.getLong("opponentId"),
        mode = MatchMode.of(o.optInt("mode")),
        myGamesWon = o.optInt("myGamesWon"), oppGamesWon = o.optInt("oppGamesWon"),
        result = MatchResult.of(o.optInt("result", 2)),
        note = o.optString("note").takeIf { it.isNotEmpty() },
    )

    // ---------------------------------------------------------------- games

    private fun gameToJson(v: Game) = JSONObject()
        .put("id", v.id).put("matchId", v.matchId).put("index", v.gameIndex)
        .put("myScore", v.myScore).put("oppScore", v.oppScore).put("result", v.result.code)

    private fun gameFromJson(o: JSONObject) = Game(
        id = o.getLong("id"), matchId = o.getLong("matchId"),
        gameIndex = o.optInt("index"),
        myScore = o.optInt("myScore"), oppScore = o.optInt("oppScore"),
        result = GameResult.of(o.optInt("result", 2)),
    )

    // ---------------------------------------------------------------- rallies

    private fun rallyToJson(v: Rally) = JSONObject()
        .put("id", v.id).put("gameId", v.gameId).put("seq", v.seq)
        .put("scorer", v.scorer.code).put("reasonTag", v.reasonTag)
        .put("note", v.note).put("audioPath", v.audioPath).put("isReviewed", v.isReviewed)

    private fun rallyFromJson(o: JSONObject) = Rally(
        id = o.getLong("id"), gameId = o.getLong("gameId"), seq = o.optInt("seq"),
        scorer = Scorer.of(o.optInt("scorer", 1)),
        reasonTag = o.optString("reasonTag").takeIf { it.isNotEmpty() },
        note = o.optString("note").takeIf { it.isNotEmpty() },
        audioPath = o.optString("audioPath").takeIf { it.isNotEmpty() },
        isReviewed = o.optBoolean("isReviewed"),
    )

    // ---------------------------------------------------------------- string_jobs

    private fun stringJobToJson(v: StringJob) = JSONObject()
        .put("id", v.id).put("racketId", v.racketId).put("date", v.date)
        .put("lineModel", v.lineModel).put("tensionLbs", v.tensionLbs)
        .put("priceYuan", v.priceYuan).put("note", v.note)

    private fun stringJobFromJson(o: JSONObject) = StringJob(
        id = o.getLong("id"), racketId = o.getLong("racketId"), date = o.getLong("date"),
        lineModel = o.optString("lineModel"), tensionLbs = o.optInt("tensionLbs"),
        priceYuan = o.optDouble("priceYuan"),
        note = o.optString("note").takeIf { it.isNotEmpty() },
    )

    // ---------------------------------------------------------------- shuttle_usages

    private fun shuttleUsageToJson(v: ShuttleUsage) = JSONObject()
        .put("id", v.id).put("sessionId", v.sessionId).put("shuttleId", v.shuttleId)
        .put("count", v.count).put("unitCost", v.unitCost)

    private fun shuttleUsageFromJson(o: JSONObject) = ShuttleUsage(
        id = o.getLong("id"), sessionId = o.getLong("sessionId"),
        shuttleId = o.getLong("shuttleId"),
        count = o.optDouble("count"), unitCost = o.optDouble("unitCost"),
    )

    // ---------------------------------------------------------------- reminders

    private fun reminderToJson(v: Reminder) = JSONObject()
        .put("id", v.id).put("type", v.type.code)
        .put("thresholdDays", v.thresholdDays).put("enabled", v.enabled)

    private fun reminderFromJson(o: JSONObject) = Reminder(
        id = o.getLong("id"), type = ReminderType.of(o.optInt("type")),
        thresholdDays = o.optInt("thresholdDays"), enabled = o.optBoolean("enabled", true),
    )

    // ---------------------------------------------------------------- dashboard_cards

    private fun dashboardCardToJson(v: DashboardCard) = JSONObject()
        .put("id", v.id).put("metricKey", v.metricKey)
        .put("orderIndex", v.orderIndex).put("visible", v.visible)

    private fun dashboardCardFromJson(o: JSONObject) = DashboardCard(
        id = o.getLong("id"), metricKey = o.optString("metricKey"),
        orderIndex = o.optInt("orderIndex"), visible = o.optBoolean("visible", true),
    )
}

/** 让 BackupDao 支持按表名清空，避免在恢复逻辑里写一大段 when */
private suspend fun BackupDao.clearByName(name: String) {
    when (name) {
        "rallies" -> clearRallies()
        "games" -> clearGames()
        "matches" -> clearMatches()
        "string_jobs" -> clearStringJobs()
        "shuttle_usages" -> clearShuttleUsages()
        "sessions" -> clearSessions()
        "opponents" -> clearOpponents()
        "rackets" -> clearRackets()
        "shuttles" -> clearShuttles()
        "gear_items" -> clearGearItems()
        "gear_tags" -> clearGearTags()
        "reminders" -> clearReminders()
        "dashboard_cards" -> clearDashboardCards()
    }
}
