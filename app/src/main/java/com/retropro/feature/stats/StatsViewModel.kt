package com.retropro.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retropro.data.db.StatsOverview
import com.retropro.data.model.DashboardCard
import com.retropro.data.db.TagCount
import com.retropro.data.repository.DiaryRepository
import com.retropro.data.repository.StatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 统计页状态。
 *
 * ## 指标卡片是数据驱动的
 *
 * 页面上方那排卡片**不是写死的**：来自 [DashboardCard] 表
 * （`metricKey` 决定算什么 / `visible` 决定显不显示 / `orderIndex` 决定排哪里），
 * 用户可以在页面上自己增删排序。口径集中在 [MetricKey]。
 *
 * ## 为什么种子在 ViewModel 里
 *
 * 首次使用时铺 4 张默认卡片。放在 Repository 的 `seedIfEmpty()` 里由这里调用，
 * 而不是在 Application 里 —— Application 阶段数据库可能还没被触碰，
 * 在那里初始化会拖慢冷启动（与 [com.retropro.di.AppGraph] 的懒加载约定一致）。
 */
class StatsViewModel(
    private val stats: StatsRepository,
    private val diary: DiaryRepository,
) : ViewModel() {

    val overview: StateFlow<StatsOverview?> = stats.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val opponentStats: StateFlow<List<com.retropro.data.db.OpponentGamesStat>> = stats.observeOpponentGameStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cards: StateFlow<List<DashboardCard>> = stats.observeCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _topLossReasons = MutableStateFlow<List<TagCount>>(emptyList())
    val topLossReasons: StateFlow<List<TagCount>> = _topLossReasons.asStateFlow()

    init {
        viewModelScope.launch { stats.seedIfEmpty() }
        refreshLossReasons()
    }

    fun refreshLossReasons() {
        viewModelScope.launch { _topLossReasons.value = diary.topLossReasons(limit = 5) }
    }

    fun moveUp(card: DashboardCard) = viewModelScope.launch { stats.moveUp(card) }

    fun moveDown(card: DashboardCard) = viewModelScope.launch { stats.moveDown(card) }

    fun setVisible(card: DashboardCard, visible: Boolean) =
        viewModelScope.launch { stats.setVisible(card, visible) }

    fun addCard(metricKey: String) = viewModelScope.launch { stats.addCard(metricKey) }

    fun remove(card: DashboardCard) = viewModelScope.launch { stats.remove(card) }

    /** 显示全部指标卡。整体状态，收在仓储层一次调用里（不要 UI 侧 forEach） */
    fun showAll() = viewModelScope.launch { stats.showAll() }

    /** 重置为默认卡片集。删光+重建必须在同一事务里，否则 orderIndex 会因并发而重复 */
    fun resetToDefault() = viewModelScope.launch { stats.resetToDefault() }
}
