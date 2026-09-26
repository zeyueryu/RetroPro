package com.retropro.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retropro.data.backup.BackupRepository
import com.retropro.data.model.ReminderType
import com.retropro.data.reminder.ReminderChecker
import com.retropro.data.reminder.ReminderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 「我的」页状态：提醒 + 备份 */
class ProfileViewModel(
    private val reminder: ReminderChecker,
    private val backup: BackupRepository,
) : ViewModel() {

    private val _reminders = MutableStateFlow<List<ReminderStatus>>(emptyList())
    val reminders: StateFlow<List<ReminderStatus>> = _reminders.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 最近一次导出的文件路径（导出后显示给用户） */
    private val _lastExportPath = MutableStateFlow<String?>(null)
    val lastExportPath: StateFlow<String?> = _lastExportPath.asStateFlow()

    init {
        // ⚠️ seed 与读取必须**串行**。
        //  早期写成两个并发协程：refresh() 在 seedIfEmpty() 完成前就读了一次，
        //  读到空列表后 UI 永远停在「加载中…」。种子写入是有副作用的初始化，
        //  必须先于第一次读取。
        viewModelScope.launch {
            reminder.seedIfEmpty()
            _reminders.value = reminder.statuses()
        }
    }

    fun refresh() {
        viewModelScope.launch { _reminders.value = reminder.statuses() }
    }

    fun setEnabled(type: ReminderType, enabled: Boolean) {
        viewModelScope.launch {
            reminder.setEnabled(type, enabled)
            _reminders.value = reminder.statuses()
        }
    }

    fun setThreshold(type: ReminderType, days: Int) {
        viewModelScope.launch {
            reminder.setThreshold(type, days)
            _reminders.value = reminder.statuses()
        }
    }

    /** 导出备份到 App 专属外部目录，返回写好的文件 */
    fun export(onDone: (File) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val file = backup.exportToFile()
            _busy.value = false
            _lastExportPath.value = file.absolutePath
            onDone(file)
        }
    }

    /**
     * 从文件恢复。**会清空现有数据** —— UI 必须先确认。
     *
     * 恢复成功后要刷新提醒（数据换了，"上次打球"也就变了）。
     */
    fun importFromStream(stream: java.io.InputStream, onDone: (Throwable?) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val error = runCatching { backup.importFromStream(stream) }
                .exceptionOrNull()
            _busy.value = false
            if (error == null) refresh()
            onDone(error)
        }
    }
}
