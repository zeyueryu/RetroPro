package com.retropro.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记录主线聚合的四张表：场次 → 比赛 → 局 → 球。
 *
 * 对应《羽毛球日记-App-设计方案.md》§5.2。字段与文档逐条对齐，
 * 仅有一处列名调整（见 [Game.index] 的注释）。
 *
 * ## 为什么用枚举 + TypeConverter 而不是裸 Int
 *
 * 文档里 type / mode / result 都是 `Int`（0=训练 1=比赛 …）。直接存 Int 会在
 * 读代码时不断需要回查含义，也容易写错。这里在**数据库里仍然存 Int**（与文档一致、
 * 迁移简单），但在 Kotlin 侧用枚举表达，由 [com.retropro.data.db.Converters] 转换。
 */

/** 场次类型。文档：0=训练 1=比赛 2=自由打 */
enum class SessionType(val code: Int, val label: String) {
    TRAINING(0, "训练"),
    MATCH(1, "比赛"),
    FREE_PLAY(2, "自由打"),
    ;

    companion object {
        fun of(code: Int): SessionType = entries.firstOrNull { it.code == code } ?: FREE_PLAY
    }
}

/** 单打 / 双打 */
enum class MatchMode(val code: Int, val label: String) {
    SINGLES(0, "单打"),
    DOUBLES(1, "双打"),
    ;

    companion object {
        fun of(code: Int): MatchMode = entries.firstOrNull { it.code == code } ?: SINGLES
    }
}

/** 比赛结果。文档：0=胜 1=负 2=平 */
enum class MatchResult(val code: Int, val label: String) {
    WIN(0, "胜"),
    LOSS(1, "负"),
    DRAW(2, "平"),
    ;

    companion object {
        fun of(code: Int): MatchResult = entries.firstOrNull { it.code == code } ?: DRAW
    }
}

/**
 * 局结果。
 *
 * ⚠️ **相对设计方案 §5.2 的一处有理由偏离**：文档写的是「0=胜 1=负」两态，
 * 但**局是在打开计分板的那一刻就创建出来的**，此时它既没胜也没负。
 * 只有两态时，"还没打完"只能默认成负，实测直接导致统计页
 * 把 4 局 0:0 的未开打比赛算成「负 4 局」。
 *
 * 因此加第三态 [ONGOING]，并把 [Game.result] 的默认值设为它。
 * 数据库里仍存 Int，仅新增一个取值，迁移成本为零。
 */
enum class GameResult(val code: Int, val label: String) {
    WIN(0, "胜"),
    LOSS(1, "负"),

    /** 已创建但未分出胜负 —— 默认态 */
    ONGOING(2, "进行中"),
    ;

    companion object {
        fun of(code: Int): GameResult = entries.firstOrNull { it.code == code } ?: ONGOING
    }
}

/** 这一球谁得分。文档：0=我得分 1=对方得分 */
enum class Scorer(val code: Int, val label: String) {
    ME(0, "我"),
    OPPONENT(1, "对方"),
    ;

    companion object {
        fun of(code: Int): Scorer = entries.firstOrNull { it.code == code } ?: OPPONENT
    }
}

/**
 * sessions —— 场次（日记主表）。
 *
 * 一场球 = 一条 session。它下面挂 0..n 场 match（vs 不同对手），
 * 另有本场使用的球拍与用球消耗。
 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 日期时间戳（毫秒） */
    val date: Long,
    /** 场馆名。做"最近使用快捷选择"时直接 `SELECT DISTINCT venue ORDER BY date DESC`，不另建表 */
    val venue: String = "",
    /** 时长（分钟） */
    val durationMin: Int = 0,
    /** 费用（元） */
    val feeYuan: Double = 0.0,
    /** 体力消耗 1–5 */
    val stamina: Int = 3,
    val type: SessionType = SessionType.MATCH,
    /** 整体心得（长文本） */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * matches —— 比赛（一个场次里 vs 某个对手的一场）。
 *
 * `myGamesWon` / `oppGamesWon` 是**冗余字段**：它们本可以从 games 表算出来，
 * 但统计页要频繁读"本月赢了几场"，冗余一份避免每次都聚合。写入时由仓储层统一维护。
 */
@Entity(
    tableName = "matches",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Opponent::class,
            parentColumns = ["id"],
            childColumns = ["opponentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sessionId"), Index("opponentId")],
)
data class Match(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val opponentId: Long? = null,
    val mode: MatchMode = MatchMode.SINGLES,
    val myGamesWon: Int = 0,
    val oppGamesWon: Int = 0,
    val result: MatchResult = MatchResult.DRAW,
    /** 本场总结 */
    val note: String? = null,
)

/**
 * games —— 局。
 *
 * ⚠️ `index` 是 SQLite 的保留字。Room 会把列名原样写进建表语句，为避免踩坑，
 * Kotlin 侧属性叫 [gameIndex]，**数据库列名仍按文档叫 `index`**。
 * 这样一来 schema 与文档一致，代码侧也不会有人误用保留字。
 */
@Entity(
    tableName = "games",
    foreignKeys = [
        ForeignKey(
            entity = Match::class,
            parentColumns = ["id"],
            childColumns = ["matchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("matchId")],
)
data class Game(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    /** 第几局（1/2/3） */
    @ColumnInfo(name = "index") val gameIndex: Int,
    val myScore: Int = 0,
    val oppScore: Int = 0,
    /**
     * 局结果。**这是派生列，唯一事实来源是 [myScore] / [oppScore]。**
     *
     * 所有读取方（统计、比赛汇总）都必须用比分比较，
     * 不要直接读这个字段 —— 它只是 [com.retropro.data.repository.DiaryRepository]
     * 在同步时顺手写下的一份镜像，方便需要按结果排序/过滤时少算一次比较。
     */
    val result: GameResult = GameResult.ONGOING,
)

/**
 * rallies —— 球。**逐球复盘的载体，本 App 的核心表。**
 *
 * [note] 是"每一球的心得"，是整个产品存在的理由；
 * [reasonTag] 用于快速归类失分原因；[audioPath] 指向语音片段（联网识别后落地的本地文件）。
 */
@Entity(
    tableName = "rallies",
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["id"],
            childColumns = ["gameId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("gameId")],
)
data class Rally(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: Long,
    /** 本局第几球 */
    val seq: Int,
    val scorer: Scorer,
    /** 失分 / 得分原因标签 */
    val reasonTag: String? = null,
    /** ★ 每一球的心得 */
    val note: String? = null,
    /** 语音片段路径（可选） */
    val audioPath: String? = null,
    /**
     * 是否已复盘。
     *
     * **口径：给了原因标签 或 写了心得，都算已复盘。**
     * 标签本身就是复盘的答案（"这一球为什么丢"），只认心得会把
     * "快速点了个标签"的用户判成没复盘 —— 实测确实出现了这个问题。
     *
     * ⚠️ 这是**唯一**的判定位置：所有 SQL 查询一律读这一列（`isReviewed = 1`），
     * 不要再把判定表达式抄进各个 @Query 里，否则口径一定会走偏。
     */
    val isReviewed: Boolean = false,
)
