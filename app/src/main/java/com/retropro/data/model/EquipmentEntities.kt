package com.retropro.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 装备相关四张表：球拍 → 穿线记录；羽毛球型号 → 用球明细。
 *
 * 对应《羽毛球日记-App-设计方案.md》§5.2。
 *
 * ## 设计取舍（按用户明确要求）
 *
 * 穿线记录**不做寿命衰减计算**，只记「日期 + 价格」，另加线型号与磅数，
 * 用于区分同一支拍的多次穿线。所以 [StringJob] 里没有"用了几小时/剩多少寿命"这类字段。
 */

/** rackets —— 球拍 */
@Entity(tableName = "rackets")
data class Racket(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String = "",
    /** 型号（如 天斧 100ZZ） */
    val model: String = "",
    val note: String? = null,
    /** 是否在用。退役的拍子保留历史穿线记录 */
    val isActive: Boolean = true,
)

/**
 * string_jobs —— 穿线记录。一支拍可以有多条。
 *
 * 这是"穿线历史分层"的落点：列表按日期倒序，最新的在最上面。
 */
@Entity(
    tableName = "string_jobs",
    foreignKeys = [
        ForeignKey(
            entity = Racket::class,
            parentColumns = ["id"],
            childColumns = ["racketId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("racketId")],
)
data class StringJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val racketId: Long,
    /** ★ 拉线日期 —— 用户明确只记这个 */
    val date: Long,
    /** 线型号（如 BG65 / NBG95） */
    val lineModel: String = "",
    val tensionLbs: Int = 0,
    /** ★ 价格（元）—— 用户明确要记 */
    val priceYuan: Double = 0.0,
    val note: String? = null,
)

/** shuttles —— 羽毛球型号 */
@Entity(tableName = "shuttles")
data class Shuttle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String = "",
    /** 型号（如 AS-05 / A+60） */
    val model: String = "",
    val note: String? = null,
)

/** shuttle_usages —— 用球明细（某场次消耗了哪种球、多少） */
@Entity(
    tableName = "shuttle_usages",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Shuttle::class,
            parentColumns = ["id"],
            childColumns = ["shuttleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("shuttleId")],
)
data class ShuttleUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val shuttleId: Long,
    /** 消耗数量。支持 0.5 筒 / 个数，所以是 Double */
    val count: Double = 0.0,
    /** 单价 */
    val unitCost: Double = 0.0,
)


// ---------------------------------------------------------------- 其他装备（球鞋/球衣/手胶）

/**
 * gear_items —— 球鞋 / 球衣 / 手胶等装备条目。
 *
 * 与 [Racket] 分表：球拍有穿线历史这条独立的生命线，其余装备没有，
 * 一张通表 + category 更省事。
 */
@Entity(tableName = "gear_items")
data class GearItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SHOES / JERSEY / GRIP */
    val category: String,
    val name: String,
    /** 品牌 —— 从标签里选，也允许手填 */
    val brand: String = "",
    val priceYuan: Double = 0.0,
    /** 购入日期（epoch day，与 sessions.date 同源） */
    val boughtDate: Long = 0,
    val note: String? = null,
    /** 在用/退役 */
    val isActive: Boolean = true,
)

/**
 * gear_tags —— 可自定义的装备标签（预设 16 个羽毛球品牌，Migration 时 seed）。
 *
 * 标签是**平面的名字集合**，不与装备建立外键关联：装备条目存 brand 字符串即可，
 * 标签表只负责「可选词库」的管理（增删）。
 */
@Entity(tableName = "gear_tags", indices = [Index(value = ["name"], unique = true)])
data class GearTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)
