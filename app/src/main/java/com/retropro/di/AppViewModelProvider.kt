package com.retropro.di

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.retropro.feature.equipment.EquipmentViewModel
import com.retropro.feature.profile.ProfileViewModel
import com.retropro.feature.record.RecordViewModel
import com.retropro.feature.stats.StatsViewModel

/**
 * ViewModel 工厂集合。
 *
 * 因为用的是 [AppGraph] 这个极简服务定位器（没有 DI 框架），
 * 所以需要显式告诉 ViewModel 从哪拿依赖。集中放这里，
 * 新增 ViewModel 时只改一处。
 */
object AppViewModelProvider {

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { RecordViewModel(AppGraph.diary) }
        initializer { StatsViewModel(AppGraph.stats, AppGraph.diary) }
        initializer { EquipmentViewModel(AppGraph.equipment) }
        initializer { ProfileViewModel(AppGraph.reminder, AppGraph.backup) }
    }
}
