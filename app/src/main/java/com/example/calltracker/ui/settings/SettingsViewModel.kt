package com.example.calltracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.SimCard
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.SimCardSource
import com.example.calltracker.domain.usecase.SetWorkSimUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val loading: Boolean = true,
    val sims: List<SimCard> = emptyList(),
    val workSimSlot: Int? = null,
    val updating: Boolean = false,
    val autoCreateLeads: Boolean = true,
)

sealed interface SettingsEvent {
    data class WorkSimUpdated(val updatedCalls: Int) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val simCards: SimCardSource,
    private val setWorkSim: SetWorkSimUseCase,
) : ViewModel() {

    private val sims = MutableStateFlow<List<SimCard>?>(null)
    private val updating = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> =
        combine(sims, settings.observeWorkSimSlot(), updating, settings.observeAutoCreateLeads()) { sims, slot, updating, autoLeads ->
            SettingsUiState(
                loading = sims == null,
                sims = sims.orEmpty(),
                workSimSlot = slot,
                updating = updating,
                autoCreateLeads = autoLeads,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    init {
        refreshSims()
    }

    /** SIMs can be swapped or the phone permission granted while the app is in the background. */
    fun refreshSims() {
        viewModelScope.launch { sims.value = simCards.activeSims() }
    }

    fun setAutoCreateLeads(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoCreateLeads(enabled) }
    }

    fun selectWorkSim(slot: Int?) {
        if (updating.value) return
        updating.value = true
        viewModelScope.launch {
            // Compare with the stored value, not [state]: it's stale while nothing collects it.
            if (slot != settings.workSimSlot()) _events.send(SettingsEvent.WorkSimUpdated(setWorkSim(slot)))
            updating.value = false
        }
    }
}
