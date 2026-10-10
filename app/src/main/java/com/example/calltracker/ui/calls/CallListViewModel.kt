package com.example.calltracker.ui.calls

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.CallListQuery
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.sync.SweepRequester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Upload state as the user thinks of it; FAILED_RETRYABLE still counts as pending (it will be retried). */
enum class SyncFilter(val statuses: Set<SyncStatus>) {
    PENDING(setOf(SyncStatus.PENDING, SyncStatus.IN_FLIGHT, SyncStatus.FAILED_RETRYABLE)),
    SYNCED(setOf(SyncStatus.SYNCED)),
    FAILED(setOf(SyncStatus.FAILED_PERMANENT)),
}

/** The list's tab. Upload status is filtered on the Sync queue screen, not here. */
data class CallFilter(
    val classification: Classification? = null,
) {
    val isActive: Boolean get() = classification != null

    fun toQuery() = CallListQuery(classification = classification, syncStatuses = null)
}

/** Everything on the list screen except the rows, which are paged separately ([CallListViewModel.calls]). */
data class CallListUiState(
    val filter: CallFilter = CallFilter(),
    /** Across all calls, whatever the filter. */
    val waitingToUpload: Int = 0,
    val uploadFailed: Int = 0,
    val batteryHintDismissed: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CallListViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val settings: SettingsRepository,
    private val sweeps: SweepRequester,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Enum name in SavedStateHandle: the tab survives process death.
    private val filter: Flow<CallFilter> = savedState.getStateFlow<String?>(KEY_CLASSIFICATION, null)
        .map { classification -> CallFilter(Classification.entries.firstOrNull { it.name == classification }) }

    /** Rows, paged from the database; cached so a rotation doesn't reload from the top. */
    val calls: Flow<PagingData<CallListEntry>> = filter
        .flatMapLatest { callRepository.pagedCallList(it.toQuery()) }
        .cachedIn(viewModelScope)

    val state: StateFlow<CallListUiState> =
        combine(filter, callRepository.observeUploadCounts(), settings.observeBatteryHintDismissed()) { filter, counts, dismissed ->
            CallListUiState(filter, counts.waiting, counts.failed, dismissed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallListUiState())

    fun setClassificationFilter(classification: Classification?) {
        savedState[KEY_CLASSIFICATION] = classification?.name
    }

    /** App open / permission just granted: pick up anything the background triggers missed. */
    fun refresh() = sweeps.requestSweep()

    fun dismissBatteryHint() {
        viewModelScope.launch { settings.dismissBatteryHint() }
    }

    private companion object {
        const val KEY_CLASSIFICATION = "filter_classification"
    }
}
