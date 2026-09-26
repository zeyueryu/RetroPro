package com.retropro.feature.equipment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retropro.data.db.RacketWithLastString
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.data.model.Shuttle
import com.retropro.data.repository.EquipmentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 装备页状态。所有可变状态都在 Room 里，ViewModel 只做转发与写操作 */
class EquipmentViewModel(private val repo: EquipmentRepository) : ViewModel() {

    val rackets: StateFlow<List<RacketWithLastString>> = repo.observeRackets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shuttles: StateFlow<List<Shuttle>> = repo.observeShuttles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRacket(brand: String, model: String) = viewModelScope.launch {
        repo.addRacket(brand, model)
    }

    fun addStringJob(
        racketId: Long,
        date: Long,
        lineModel: String,
        tensionLbs: Int,
        priceYuan: Double,
    ) = viewModelScope.launch {
        repo.addStringJob(racketId, date, lineModel, tensionLbs, priceYuan)
    }

    fun addShuttle(brand: String, model: String) = viewModelScope.launch {
        repo.addShuttle(brand, model)
    }

    // ---- 其他装备（球鞋/球衣/手胶）+ 标签

    val gearTags: StateFlow<List<GearTag>> = repo.observeGearTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val gearItemsCache = mutableMapOf<String, StateFlow<List<GearItem>>>()

    fun gearItems(category: String): StateFlow<List<GearItem>> = gearItemsCache.getOrPut(category) {
        repo.observeGear(category)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun gearCosts(category: String): StateFlow<Double> =
        repo.observeGearCosts()
            .map { list -> list.firstOrNull { it.category == category }?.total ?: 0.0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    init {
        viewModelScope.launch { repo.seedDefaultTagsIfEmpty() }
    }

    fun addGear(category: String, name: String, brand: String, priceYuan: Double, boughtDate: Long) =
        viewModelScope.launch { repo.addGear(category, name, brand, priceYuan, boughtDate) }

    fun deleteGear(item: GearItem) = viewModelScope.launch { repo.deleteGear(item) }

    fun setGearActive(item: GearItem, active: Boolean) =
        viewModelScope.launch { repo.setGearActive(item, active) }

    fun addGearTag(name: String) = viewModelScope.launch { repo.addGearTag(name) }

    fun deleteGearTag(tag: GearTag) = viewModelScope.launch { repo.deleteGearTag(tag) }
}
