package com.retropro.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retropro.data.db.SessionListItem
import com.retropro.data.model.MatchMode
import com.retropro.data.model.SessionType
import com.retropro.data.repository.DiaryRepository
import com.retropro.data.repository.QuickStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「记一场」主线的状态。
 *
 * 这一屏承担两个动作，按使用频率排序：
 *  1. **快速开一场** —— 走进球馆、按下就能开始计分，场馆/费用/体力赛后补录。
 *     这是最高频路径，所以放最显眼的位置，且只要求填"对手"这一项（可留空）。
 *  2. **补录一场** —— 赛后回忆录入，字段完整（含时长、费用、体力、心得）。
 */
class RecordViewModel(private val repo: DiaryRepository) : ViewModel() {

    val sessions: StateFlow<List<SessionListItem>> = repo.observeSessionList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 最近用过的场馆，做快捷选择 */
    private val _recentVenues = MutableStateFlow<List<String>>(emptyList())
    val recentVenues: StateFlow<List<String>> = _recentVenues.asStateFlow()

    /** 新建场次表单的展开状态由 UI 持有，这里只负责数据动作 */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refreshVenues() {
        viewModelScope.launch { _recentVenues.value = repo.recentVenues(limit = 6) }
    }

    /**
     * 快速开一场。返回新场次 id（通过 [onCreated] 回调），由 UI 决定下一步去哪。
     *
     * 这一步在仓储层是一个事务：场次 + 对阵 + 第 1 局一起建出来，
     * 所以 UI 拿到 id 后可以直接进计分板。
     */
    fun startQuickMatch(
        venue: String,
        opponentName: String?,
        mode: MatchMode,
        onCreated: (QuickStart) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            val result = repo.startQuickMatch(venue = venue, opponentName = opponentName, mode = mode)
            _busy.value = false
            onCreated(result)
        }
    }

    /** 完整补录一场 */
    fun createSession(
        venue: String,
        durationMin: Int,
        feeYuan: Double,
        stamina: Int,
        type: SessionType,
        note: String?,
        opponentName: String?,
        mode: MatchMode,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            val id = repo.createSession(
                venue = venue,
                durationMin = durationMin,
                feeYuan = feeYuan,
                stamina = stamina,
                type = type,
                note = note,
                opponentName = opponentName,
                mode = mode,
            )
            _busy.value = false
            onCreated(id)
        }
    }

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            repo.session(sessionId)?.let { repo.deleteSession(it) }
        }
    }
}
