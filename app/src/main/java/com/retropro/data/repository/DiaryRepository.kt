package com.retropro.data.repository

import androidx.room.withTransaction
import com.retropro.data.db.AppDatabase
import com.retropro.data.db.MatchWithOpponent
import com.retropro.data.db.SessionGameItem
import com.retropro.data.db.SessionListItem
import com.retropro.data.db.TagCount
import com.retropro.data.db.StatsOverview
import com.retropro.data.model.Game
import com.retropro.data.model.Match
import com.retropro.data.model.MatchMode
import com.retropro.data.model.Opponent
import com.retropro.data.model.Rally
import com.retropro.data.model.Scorer
import com.retropro.data.model.Session
import com.retropro.data.model.SessionType
import kotlinx.coroutines.flow.Flow

/**
 * 记录主线的仓储层：场次 → 比赛 → 局 → 球。
 *
 * ## 为什么所有写操作都放在这里
 *
 * 这几张表之间有**跨表不变式**，散在 ViewModel 里写早晚会写漏：
 *
 *  1. `games.myScore / oppScore` 必须等于 `rallies` 的实际统计
 *  2. `games.result` 必须与比分一致
 *  3. `matches.myGamesWon / oppGamesWon / result` 必须与 games 一致（冗余字段）
 *  4. `rallies.seq` 删除后必须重排连续
 *
 * 因此每条会改动这些数据的操作，都在一个事务里跑完 [syncFromRallies] 那条链路。
 *
 * 计分板那条"单击加分"的高频路径也走这里 —— 一次点击 = 插一条 rally + 同步三层汇总，
 * 都在同一事务，不会出现"分数变了但局结果没变"的中间态。
 */
/** 快速开一场的结果：场次与对阵都已建好，可直接进计分板 */
data class QuickStart(val sessionId: Long, val matchId: Long)

class DiaryRepository(private val db: AppDatabase) {

    private val sessionDao = db.sessionDao()
    private val matchDao = db.matchDao()
    private val gameDao = db.gameDao()
    private val rallyDao = db.rallyDao()
    private val opponentDao = db.opponentDao()

    // ---------------------------------------------------------------- 读

    fun observeSessionList(): Flow<List<SessionListItem>> = sessionDao.observeListItems()

    fun observeStats(): Flow<StatsOverview> = sessionDao.observeStats()

    fun observeSession(id: Long): Flow<Session?> = sessionDao.observe(id)

    suspend fun session(id: Long): Session? = sessionDao.byId(id)

    fun observeMatches(sessionId: Long): Flow<List<Match>> = matchDao.observeBySession(sessionId)

    fun observeMatchesWithOpponent(sessionId: Long): Flow<List<MatchWithOpponent>> =
        matchDao.observeWithOpponent(sessionId)

    /** 某场次下所有局（跨全部对阵），带逐球与复盘计数 */
    fun observeSessionGames(sessionId: Long): Flow<List<SessionGameItem>> =
        sessionDao.observeSessionGames(sessionId)

    fun observeTagCounts(gameId: Long): Flow<List<TagCount>> =
        rallyDao.observeTagCounts(gameId)

    fun observeGames(matchId: Long): Flow<List<Game>> = gameDao.observeByMatch(matchId)

    fun observeRallies(gameId: Long): Flow<List<Rally>> = rallyDao.observeByGame(gameId)

    suspend fun rallies(gameId: Long): List<Rally> = rallyDao.byGame(gameId)

    suspend fun recentVenues(limit: Int = 5): List<String> = sessionDao.recentVenues(limit)

    suspend fun topLossReasons(limit: Int = 5): List<TagCount> =
        rallyDao.topReasons(Scorer.OPPONENT, limit)

    // ---------------------------------------------------------------- 写：场次

    suspend fun createSession(
        date: Long = System.currentTimeMillis(),
        venue: String,
        durationMin: Int,
        feeYuan: Double,
        stamina: Int,
        type: SessionType,
        note: String?,
        opponentName: String?,
        mode: MatchMode,
    ): Long = db.withTransaction {
        val sessionId = sessionDao.insert(
            Session(
                date = date,
                venue = venue.trim(),
                durationMin = durationMin,
                feeYuan = feeYuan,
                stamina = stamina,
                type = type,
                note = note?.takeIf { it.isNotBlank() },
            ),
        )
        // 只有"比赛"类型才自动建一场 match；训练/自由打不需要对手
        if (type == SessionType.MATCH) {
            createMatchInternal(sessionId, opponentName, mode)
        }
        sessionId
    }

    /**
     * 「快速开一场」—— 走进球馆最快能落地的路径。
     *
     * 一次事务建出 场次 + 一场对阵 + 第 1 局，直接就能开始计分，
     * 场馆/费用/体力这些赛后补录。
     */
    suspend fun startQuickMatch(
        venue: String,
        opponentName: String?,
        mode: MatchMode = MatchMode.SINGLES,
    ): QuickStart = db.withTransaction {
        val sessionId = sessionDao.insert(
            Session(
                date = System.currentTimeMillis(),
                venue = venue.trim(),
                type = SessionType.MATCH,
            ),
        )
        val matchId = createMatchInternal(sessionId, opponentName, mode)
        gameDao.insert(Game(matchId = matchId, gameIndex = 1))
        QuickStart(sessionId, matchId)
    }

    suspend fun updateSession(session: Session) = sessionDao.update(session)

    suspend fun deleteSession(session: Session) = sessionDao.delete(session)

    // ---------------------------------------------------------------- 写：比赛 / 局

    suspend fun addMatch(
        sessionId: Long,
        opponentName: String?,
        mode: MatchMode,
    ): Long = db.withTransaction { createMatchInternal(sessionId, opponentName, mode) }

    /**
     * 找同名对手复用，找不到才新建。
     * 避免"张伟""张伟 "这种重复条目把交手次数拆散。
     */
    private suspend fun resolveOpponent(name: String?): Long? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return opponentDao.byName(trimmed)?.id ?: opponentDao.insert(Opponent(name = trimmed))
    }

    private suspend fun createMatchInternal(
        sessionId: Long,
        opponentName: String?,
        mode: MatchMode,
    ): Long {
        val opponentId = resolveOpponent(opponentName)
        return matchDao.insert(
            Match(sessionId = sessionId, opponentId = opponentId, mode = mode),
        )
    }

    /**
     * 编辑对阵的对手 / 赛制（场次详情页「编辑对手 / 赛制」）。
     * 对手走 [resolveOpponent]：按名字复用已有 Opponent，没有则新建；空名 = 未记对手。
     */
    suspend fun updateMatchInfo(matchId: Long, opponentName: String?, mode: MatchMode) =
        db.withTransaction {
            val m = matchDao.byId(matchId) ?: return@withTransaction
            matchDao.update(m.copy(opponentId = resolveOpponent(opponentName), mode = mode))
        }

    suspend fun deleteMatch(match: Match) = db.withTransaction {
        matchDao.delete(match)
    }

    /** 本场最新一局的 id；本场还没有局时返回 null */
    suspend fun latestGameId(matchId: Long): Long? = gameDao.latestGameId(matchId)

    /** 新增一局，局号自动递增 */
    suspend fun addGame(matchId: Long): Long = db.withTransaction {
        val index = gameDao.maxIndex(matchId) + 1
        gameDao.insert(Game(matchId = matchId, gameIndex = index))
    }

    suspend fun deleteGame(game: Game) = db.withTransaction {
        gameDao.delete(game)
        matchDao.recount(game.matchId)
    }

    // ---------------------------------------------------------------- 写：逐球

    /**
     * 记一球。**计分板单击加分的唯一入口。**
     *
     * @param note 这一球的心得。非空即视为"已复盘"，直接决定统计页的复盘局数。
     */
    suspend fun addRally(
        gameId: Long,
        scorer: Scorer,
        reasonTag: String? = null,
        note: String? = null,
        audioPath: String? = null,
    ): Long = db.withTransaction {
        val seq = rallyDao.maxSeq(gameId) + 1
        val cleanNote = note?.takeIf { it.isNotBlank() }
        val cleanTag = reasonTag?.takeIf { it.isNotBlank() }
        val id = rallyDao.insert(
            Rally(
                gameId = gameId,
                seq = seq,
                scorer = scorer,
                reasonTag = cleanTag,
                note = cleanNote,
                audioPath = audioPath,
                // 标签或心得任一存在即算已复盘
                isReviewed = cleanNote != null || cleanTag != null,
            ),
        )
        syncFromRallies(gameId)
        id
    }

    /** 给某一球补心得（长按唤起的标签面板 / 赛后补录都走这里） */
    suspend fun updateRally(rally: Rally) = db.withTransaction {
        val cleanNote = rally.note?.takeIf { it.isNotBlank() }
        val cleanTag = rally.reasonTag?.takeIf { it.isNotBlank() }
        rallyDao.update(
            rally.copy(
                note = cleanNote,
                reasonTag = cleanTag,
                isReviewed = cleanNote != null || cleanTag != null,
            ),
        )
        syncFromRallies(rally.gameId)
    }

    /** 撤销最后一球（计分板上的「撤销」） */
    suspend fun undoLastRally(gameId: Long) = db.withTransaction {
        rallyDao.deleteLast(gameId)
        // 删除会在序号上留洞，重排成连续的 1..n，否则"第几球"显示会跳号
        rallyDao.byGame(gameId).forEachIndexed { index, rally ->
            if (rally.seq != index + 1) rallyDao.update(rally.copy(seq = index + 1))
        }
        syncFromRallies(gameId)
    }

    suspend fun deleteRally(rally: Rally) = db.withTransaction {
        rallyDao.delete(rally)
        rallyDao.byGame(rally.gameId).forEachIndexed { index, item ->
            if (item.seq != index + 1) rallyDao.update(item.copy(seq = index + 1))
        }
        syncFromRallies(rally.gameId)
    }

    /**
     * 把逐球记录汇总回 games 与 matches。
     *
     * 三层一起刷：rallies → games（比分 + 局结果）→ matches（局数 + 场结果）。
     * 任何改动 rallies 的操作都必须调用它，这是本仓储存在的核心理由。
     *
     * 注意 `games.result` 是由比分**推导**出来的镜像（0:0 时回到"进行中"），
     * 不是独立写入的事实 —— 读取方一律比较比分。
     */
    private suspend fun syncFromRallies(gameId: Long) {
        val my = rallyDao.myScore(gameId)
        val opp = rallyDao.oppScore(gameId)
        rallyDao.syncGameScore(gameId, my, opp)
        rallyDao.deriveGameResult(gameId)
        gameDao.byId(gameId)?.let { matchDao.recount(it.matchId) }
    }
}
