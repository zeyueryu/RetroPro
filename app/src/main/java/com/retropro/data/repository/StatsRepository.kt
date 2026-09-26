package com.retropro.data.repository

import androidx.room.withTransaction
import com.retropro.data.db.AppDatabase
import com.retropro.data.db.DashboardCardDao
import com.retropro.data.db.StatsOverview
import com.retropro.data.model.DashboardCard
import kotlinx.coroutines.flow.Flow

/**
 * 统计页的配置存储。
 *
 * 「自定义统计卡片」在用户需求里是明确要预留的空间：用户自己挑想看哪几个指标、
 * 想按什么顺序看。所以指标**不是写死在 UI 里的**，而是由 [DashboardCard] 表驱动：
 * `metricKey` 决定算什么，`visible` 决定显不显示，`orderIndex` 决定排哪里。
 */
class StatsRepository(private val db: AppDatabase) {

    private val dao: DashboardCardDao = db.dashboardCardDao()

    fun observeStats(): Flow<StatsOverview> = db.sessionDao().observeStats()

    fun observeOpponentGameStats() = db.sessionDao().observeOpponentGameStats()

    fun observeCards(): Flow<List<DashboardCard>> = dao.observeAll()

    /** 首次使用时铺一组默认卡片。顺序即用户提出时点名的优先级 */
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        dao.insertDashboardCards(
            DEFAULT_CARDS.mapIndexed { index, key ->
                DashboardCard(metricKey = key, orderIndex = index)
            },
        )
    }

    suspend fun update(card: DashboardCard) = dao.update(card)

    /** 与前一张交换位置（UI 的"上移"） */
    suspend fun moveUp(card: DashboardCard) = swap(card, -1)

    /** 与后一张交换位置（UI 的"下移"） */
    suspend fun moveDown(card: DashboardCard) = swap(card, +1)

    private suspend fun swap(card: DashboardCard, delta: Int) {
        val all = dao.observeAll()
        // 这里不能用 Flow 的当前值：需要的是"这一刻"的完整排序，
        // 所以直接查一次。卡片数量是个位数，代价可以忽略。
        val list = dao.allOrdered()
        val index = list.indexOfFirst { it.id == card.id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val other = list[target]
        dao.update(card.copy(orderIndex = other.orderIndex))
        dao.update(other.copy(orderIndex = card.orderIndex))
    }

    suspend fun setVisible(card: DashboardCard, visible: Boolean) =
        dao.update(card.copy(visible = visible))

    suspend fun addCard(metricKey: String) {
        val list = dao.allOrdered()
        dao.insertDashboardCards(
            listOf(DashboardCard(metricKey = metricKey, orderIndex = list.size)),
        )
    }

    suspend fun remove(card: DashboardCard) = dao.delete(card)

    /**
     * 显示全部指标卡。
     *
     * 收在仓储层而不是让 UI 逐个 `setVisible` —— UI 侧 `forEach` 发出去的是 N 个独立协程，
     * 会被调度器任意穿插，而"全部可见"是一个**整体状态**，放在一次调用里语义才明确。
     */
    suspend fun showAll() {
        db.withTransaction {
            dao.allOrdered().forEach { card ->
                if (!card.visible) dao.update(card.copy(visible = true))
            }
        }
    }

    /**
     * 重置为默认卡片集（[DEFAULT_CARDS] 的内容与顺序）。
     *
     * ## 为什么必须收进一个事务
     *
     * 直觉做法是「UI 里先 forEach 删光、再 forEach 按默认顺序加回来」——**这是错的**。
     * [addCard] 的 `orderIndex = list.size` 是**读数据库算出来的**，而 UI 侧每个调用
     * 都是一个独立协程；N 个删 N 个加并发跑时，后发的加卡会读到**尚未提交的旧状态**，
     * 于是拿到重复的 orderIndex，顺序直接乱掉。
     *
     * 这正是项目的数据层规则：跨行不变式必须收在仓储层的事务里，
     * 散在 ViewModel / UI 里写迟早写漏。
     */
    suspend fun resetToDefault() {
        db.withTransaction {
            dao.deleteAll()
            dao.insertDashboardCards(
                DEFAULT_CARDS.mapIndexed { index, key ->
                    DashboardCard(metricKey = key, orderIndex = index)
                },
            )
        }
    }

    companion object {
        /** 默认卡片与顺序。首次使用（[seedIfEmpty]）与「重置为默认」共用同一份定义 */
        private val DEFAULT_CARDS = listOf(
            "win_games",
            "reviewed_games",
            "session_count",
            "total_fee",
        )
    }
}
