package com.retropro.data.db

import androidx.room.TypeConverter
import com.retropro.data.model.GameResult
import com.retropro.data.model.MatchMode
import com.retropro.data.model.MatchResult
import com.retropro.data.model.ReminderType
import com.retropro.data.model.Scorer
import com.retropro.data.model.SessionType

/**
 * 枚举 ↔ Int 转换器。
 *
 * 数据库里存 **Int**（与设计方案的字段定义一致，后续写迁移时不用改表），
 * Kotlin 侧用枚举表达语义。每个枚举自己的 `of(code)` 负责容错：
 * 遇到未知值回退到默认项，而不是抛异常 —— 个人 App 里宁可显示得不准，也不要崩。
 */
class Converters {

    @TypeConverter fun sessionTypeToInt(v: SessionType): Int = v.code
    @TypeConverter fun intToSessionType(v: Int): SessionType = SessionType.of(v)

    @TypeConverter fun matchModeToInt(v: MatchMode): Int = v.code
    @TypeConverter fun intToMatchMode(v: Int): MatchMode = MatchMode.of(v)

    @TypeConverter fun matchResultToInt(v: MatchResult): Int = v.code
    @TypeConverter fun intToMatchResult(v: Int): MatchResult = MatchResult.of(v)

    @TypeConverter fun gameResultToInt(v: GameResult): Int = v.code
    @TypeConverter fun intToGameResult(v: Int): GameResult = GameResult.of(v)

    @TypeConverter fun scorerToInt(v: Scorer): Int = v.code
    @TypeConverter fun intToScorer(v: Int): Scorer = Scorer.of(v)

    @TypeConverter fun reminderTypeToInt(v: ReminderType): Int = v.code
    @TypeConverter fun intToReminderType(v: Int): ReminderType = ReminderType.of(v)
}
