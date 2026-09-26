package com.retropro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.retropro.data.model.Game
import com.retropro.data.model.Match
import com.retropro.data.model.MatchMode
import com.retropro.data.model.MatchResult
import com.retropro.data.model.Rally
import com.retropro.data.model.Scorer
import com.retropro.data.model.Session
import com.retropro.data.model.SessionType
import kotlinx.coroutines.flow.Flow

/**
 * 场次列表项 —— 列表页只读这一个投影，避免把 4 张表全部加载进内存。
 *
 * 聚合口径（与设计方案 §8 统计指标定义一致）：
 *  - `myGamesWon` / `oppGamesWon`：来自 matches 的冗余计数
 *  - `rallyCount`：本场次全部球数
 *  - `reviewedRallyCount`：**写了心得**的球数（`note` 非空且非空白）
 */
data class SessionListItem(
    val id: Long,
    val date: Long,
    val venue: String,
    val type: SessionType,
    val durationMin: Int,
    val feeYuan: Double,
    val stamina: Int,
    val matchCount: Int,
    val myGamesWon: Int,
    val oppGamesWon: Int,
    val rallyCount: Int,
    val reviewedRallyCount: Int,
    /** 每局比分，按局序连接，如「21-18,19-21,21-15」 */
    val gameScores: String,
    /** 对手名列表，逗号连接，如「老陈,小李」 */
    val opponents: String,
)

/** 统计总览 —— 对应"胜利局数 / 复盘局数"，外加几个顺手能出的数 */
data class StatsOverview(
    val sessionCount: Int,
    val winGames: Int,
    val lossGames: Int,
    /** 至少打出过比分的局数。0:0 不算 —— 那是建出来还没开打的 */
    val playedGames: Int,
    val reviewedGames: Int,
    val rallyCount: Int,
    val reviewedRallyCount: Int,
    val totalFee: Double,
)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Delete
    suspend fun delete(session: Session)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): Session?

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<Session?>

    @Query(
        """
        SELECT
            s.id AS id,
            s.date AS date,
            s.venue AS venue,
            s.type AS type,
            s.durationMin AS durationMin,
            s.feeYuan AS feeYuan,
            s.stamina AS stamina,
            (SELECT COUNT(*) FROM matches m WHERE m.sessionId = s.id) AS matchCount,
            (SELECT IFNULL(SUM(m.myGamesWon), 0) FROM matches m WHERE m.sessionId = s.id) AS myGamesWon,
            (SELECT IFNULL(SUM(m.oppGamesWon), 0) FROM matches m WHERE m.sessionId = s.id) AS oppGamesWon,
            (
                SELECT COUNT(*) FROM rallies r
                JOIN games g ON r.gameId = g.id
                JOIN matches m2 ON g.matchId = m2.id
                WHERE m2.sessionId = s.id
            ) AS rallyCount,
            (
                SELECT COUNT(*) FROM rallies r
                JOIN games g ON r.gameId = g.id
                JOIN matches m2 ON g.matchId = m2.id
                WHERE m2.sessionId = s.id
                  AND r.isReviewed = 1
            ) AS reviewedRallyCount,
            IFNULL((
                SELECT GROUP_CONCAT(sc, ',') FROM (
                    SELECT g2.myScore || '-' || g2.oppScore AS sc
                    FROM games g2 JOIN matches m2 ON g2.matchId = m2.id
                    WHERE m2.sessionId = s.id ORDER BY m2.id, g2.`index`
                )
            ), '') AS gameScores,
            IFNULL((
                SELECT GROUP_CONCAT(nm, ',') FROM (
                    SELECT o.name AS nm FROM matches m3
                    LEFT JOIN opponents o ON m3.opponentId = o.id
                    WHERE m3.sessionId = s.id ORDER BY m3.id
                )
            ), '') AS opponents
        FROM sessions s
        ORDER BY s.date DESC
        """
    )
    fun observeListItems(): Flow<List<SessionListItem>>

    /** 场馆「最近使用」快捷选择。不另建表，直接取自历史记录 */
    @Query("SELECT DISTINCT venue FROM sessions WHERE TRIM(venue) != '' ORDER BY date DESC LIMIT :limit")
    suspend fun recentVenues(limit: Int = 5): List<String>

    /**
     * 某场次下所有局（跨全部对阵），带逐球数与已复盘球数。
     *
     * 排序用 `m.id, g.index` 而不是时间：同一场的对阵是按创建顺序排的，
     * 用 id 能保证"对阵 1 的第 1/2/3 局"永远连续显示在一起。
     */
    @Query(
        """
        SELECT
            g.id AS gameId,
            g.matchId AS matchId,
            g.`index` AS gameIndex,
            g.myScore AS myScore,
            g.oppScore AS oppScore,
            (SELECT COUNT(*) FROM rallies r WHERE r.gameId = g.id) AS rallyCount,
            (SELECT COUNT(*) FROM rallies r WHERE r.gameId = g.id AND r.isReviewed = 1) AS reviewedCount
        FROM games g
        JOIN matches m ON g.matchId = m.id
        WHERE m.sessionId = :sessionId
        ORDER BY m.id, g.`index`
        """
    )
    fun observeSessionGames(sessionId: Long): Flow<List<SessionGameItem>>

    /**
     * 统计总览。
     *
     * ⚠️ **胜负一律按比分比较，不读 `games.result`。**
     * 比分是唯一事实来源（由 rallies 汇总而来），`result` 只是它的镜像。
     * 之前直接读 `result` 出过一次 bug：局在打开计分板时就创建，
     * 默认值把"还没开打"算成了负。
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM sessions) AS sessionCount,
            (SELECT COUNT(*) FROM games WHERE myScore > oppScore) AS winGames,
            (SELECT COUNT(*) FROM games WHERE myScore < oppScore) AS lossGames,
            (SELECT COUNT(*) FROM games WHERE myScore > 0 OR oppScore > 0) AS playedGames,
            (
                SELECT COUNT(*) FROM games g
                WHERE EXISTS (
                    SELECT 1 FROM rallies r
                    WHERE r.gameId = g.id AND r.isReviewed = 1
                )
            ) AS reviewedGames,
            (SELECT COUNT(*) FROM rallies) AS rallyCount,
            (SELECT COUNT(*) FROM rallies WHERE isReviewed = 1) AS reviewedRallyCount,
            (SELECT IFNULL(SUM(feeYuan), 0.0) FROM sessions) AS totalFee
        """
    )
    fun observeStats(): Flow<StatsOverview>

    /** 对手局数统计：按 games 真实比分聚合（绝不读 matches.result 派生镜像） */
    @Query(
        """
        SELECT o.id AS opponentId, o.name AS name,
               COUNT(*) AS total,
               SUM(CASE WHEN g.myScore > g.oppScore THEN 1 ELSE 0 END) AS myWins,
               SUM(CASE WHEN g.oppScore > g.myScore THEN 1 ELSE 0 END) AS oppWins
        FROM games g
        JOIN matches m ON m.id = g.matchId
        LEFT JOIN opponents o ON o.id = m.opponentId
        GROUP BY o.id, o.name
        ORDER BY total DESC
        """
    )
    fun observeOpponentGameStats(): Flow<List<OpponentGamesStat>>
}

@Dao
interface MatchDao {

    @Insert
    suspend fun insert(match: Match): Long

    @Update
    suspend fun update(match: Match)

    @Delete
    suspend fun delete(match: Match)

    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun byId(id: Long): Match?

    @Query("SELECT * FROM matches WHERE sessionId = :sessionId ORDER BY id")
    suspend fun bySession(sessionId: Long): List<Match>

    @Query("SELECT * FROM matches WHERE sessionId = :sessionId ORDER BY id")
    fun observeBySession(sessionId: Long): Flow<List<Match>>

    /** 带对手名的比赛列表（场次详情页用） */
    @Query(
        """
        SELECT
            m.id AS id,
            m.sessionId AS sessionId,
            o.name AS opponentName,
            m.mode AS mode,
            m.myGamesWon AS myGamesWon,
            m.oppGamesWon AS oppGamesWon,
            m.result AS result
        FROM matches m
        LEFT JOIN opponents o ON m.opponentId = o.id
        WHERE m.sessionId = :sessionId
        ORDER BY m.id
        """
    )
    fun observeWithOpponent(sessionId: Long): Flow<List<MatchWithOpponent>>

    /** 对战次数（对手页要用）：opponentId → 交手的 match 数 */
    @Query(
        """
        SELECT opponentId AS opponentId, COUNT(*) AS count FROM matches
        WHERE opponentId IS NOT NULL
        GROUP BY opponentId
        ORDER BY count DESC
        """
    )
    suspend fun matchupCounts(): List<MatchupCount>

    /**
     * 维护 matches 上的冗余计数。
     *
     * 之所以冗余：统计页要频繁读"一共赢了几局"，每次去聚合 games 表代价不划算。
     * 写入统一走这里，保证冗余值与事实一致。
     *
     * 与统计页同一口径：**按比分比较，不读 `games.result`**。平局（含 0:0 未开打）
     * 不计入任何一方的局数 —— 这是 [MatchResult.DRAW] 的用途。
     */
    @Query(
        """
        UPDATE matches SET
            myGamesWon = (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore > g.oppScore),
            oppGamesWon = (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore < g.oppScore),
            result = CASE
                WHEN (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore > g.oppScore)
                   > (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore < g.oppScore) THEN 0
                WHEN (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore > g.oppScore)
                   < (SELECT COUNT(*) FROM games g WHERE g.matchId = :matchId AND g.myScore < g.oppScore) THEN 1
                ELSE 2
            END
        WHERE id = :matchId
        """
    )
    suspend fun recount(matchId: Long)
}

/** 对手交手次数投影 */
data class MatchupCount(val opponentId: Long, val count: Int)

/**
 * 比赛 + 对手名 —— 场次详情页的列表项。
 *
 * 用 `LEFT JOIN` 而不是 `JOIN`：对手可以为空（自己练球、临时凑的搭子），
 * `JOIN` 会把这些比赛整条丢掉。
 */
data class MatchWithOpponent(
    val id: Long,
    val sessionId: Long,
    val opponentName: String?,
    val mode: MatchMode,
    val myGamesWon: Int,
    val oppGamesWon: Int,
    val result: MatchResult,
)

/**
 * 某场次下的所有局（跨全部对阵）—— 场次详情页把局挂在对阵卡下面显示。
 *
 * `reviewedCount` 是**写了心得的球数**，它是本 App 最关键的进度指标：
 * 用户一眼能看到"这局 18 球里只有 3 球想过"。
 */
data class SessionGameItem(
    val gameId: Long,
    val matchId: Long,
    val gameIndex: Int,
    val myScore: Int,
    val oppScore: Int,
    val rallyCount: Int,
    val reviewedCount: Int,
)

/** 标签频次（失分原因分布） */
data class TagCount(val reasonTag: String, val count: Int)

/** 对手局数统计行（对手局数统计卡 + 二级页共用） */
data class OpponentGamesStat(
    val opponentId: Long,
    val name: String?,
    val total: Int,
    val myWins: Int,
    val oppWins: Int,
)

@Dao
interface GameDao {

    @Insert
    suspend fun insert(game: Game): Long

    @Update
    suspend fun update(game: Game)

    @Delete
    suspend fun delete(game: Game)

    @Query("SELECT * FROM games WHERE matchId = :matchId ORDER BY `index`")
    suspend fun byMatch(matchId: Long): List<Game>

    @Query("SELECT * FROM games WHERE matchId = :matchId ORDER BY `index`")
    fun observeByMatch(matchId: Long): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: Long): Game?

    /** 某场比赛当前的最大局号，用于"再来一局"时递增 */
    @Query("SELECT IFNULL(MAX(`index`), 0) FROM games WHERE matchId = :matchId")
    suspend fun maxIndex(matchId: Long): Int

    /**
     * 本场最新一局（局号最大的那条）。
     *
     * ⚠️ 计分板必须用它来判断"有没有局"，**不能拿 Flow 的当前值判断**：
     * Flow 首次发射前列表是空的，据此判断会误以为"本场还没有局"从而多建一局。
     * 实测直接导致进计分板就凭空多出「第 2 局」。
     */
    @Query("SELECT id FROM games WHERE matchId = :matchId ORDER BY `index` DESC LIMIT 1")
    suspend fun latestGameId(matchId: Long): Long?
}

@Dao
interface RallyDao {

    @Insert
    suspend fun insert(rally: Rally): Long

    @Update
    suspend fun update(rally: Rally)

    @Delete
    suspend fun delete(rally: Rally)

    @Query("SELECT * FROM rallies WHERE gameId = :gameId ORDER BY seq")
    suspend fun byGame(gameId: Long): List<Rally>

    @Query("SELECT * FROM rallies WHERE gameId = :gameId ORDER BY seq")
    fun observeByGame(gameId: Long): Flow<List<Rally>>

    /** 本局我方得分（以逐球记录为准，与 games.myScore 互为校验） */
    @Query("SELECT COUNT(*) FROM rallies WHERE gameId = :gameId AND scorer = 0")
    suspend fun myScore(gameId: Long): Int

    @Query("SELECT COUNT(*) FROM rallies WHERE gameId = :gameId AND scorer = 1")
    suspend fun oppScore(gameId: Long): Int

    @Query("SELECT IFNULL(MAX(seq), 0) FROM rallies WHERE gameId = :gameId")
    suspend fun maxSeq(gameId: Long): Int

    /** 撤销最后一球（计分板上的"撤销"按钮） */
    @Query("DELETE FROM rallies WHERE id = (SELECT id FROM rallies WHERE gameId = :gameId ORDER BY seq DESC LIMIT 1)")
    suspend fun deleteLast(gameId: Long): Int


    /** 按失分原因归类，用于"常丢分在哪" */
    @Query(
        """
        SELECT reasonTag, COUNT(*) AS count FROM rallies
        WHERE scorer = :scorer AND reasonTag IS NOT NULL AND TRIM(reasonTag) != ''
        GROUP BY reasonTag ORDER BY count DESC LIMIT :limit
        """
    )
    suspend fun topReasons(scorer: Scorer, limit: Int): List<TagCount>

    /**
     * 单局内的标签频次 —— 逐球复盘页顶部的"本局怎么丢的分"。
     *
     * 与 [topReasons] 的区别：那个是全局 Top N，这个是**单局全部**标签
     * （不设 limit，因为一局的标签种类本来就少，全列出来更有信息量）。
     */
    @Query(
        """
        SELECT reasonTag, COUNT(*) AS count FROM rallies
        WHERE gameId = :gameId AND reasonTag IS NOT NULL AND TRIM(reasonTag) != ''
        GROUP BY reasonTag ORDER BY count DESC, reasonTag
        """
    )
    fun observeTagCounts(gameId: Long): Flow<List<TagCount>>

    @Query("UPDATE games SET myScore = :my, oppScore = :opp WHERE id = :gameId")
    suspend fun syncGameScore(gameId: Long, my: Int, opp: Int)

    /**
     * 由比分推导 `games.result`（0=胜 1=负 2=进行中）。
     *
     * 放在 SQL 里做而不是 Kotlin 里分支：这样 `result` 的定义只存在一处，
     * 与统计、recount 的口径不会走偏。
     */
    @Query(
        """
        UPDATE games SET result = CASE
            WHEN myScore > oppScore THEN 0
            WHEN myScore < oppScore THEN 1
            ELSE 2
        END
        WHERE id = :gameId
        """
    )
    suspend fun deriveGameResult(gameId: Long)
}
