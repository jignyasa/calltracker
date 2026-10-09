package com.example.calltracker.ui.calls

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.SyncRequester
import com.example.calltracker.sync.SweepRequester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Upload state as the user thinks of it; FAILED_RETRYABLE still counts as pending (it will be retried). */
enum class SyncFilter(val statuses: Set<SyncStatus>) {
    PENDING(setOf(SyncStatus.PENDING, SyncStatus.IN_FLIGHT, SyncStatus.FAILED_RETRYABLE)),
    SYNCED(setOf(SyncStatus.SYNCED)),
    FAILED(setOf(SyncStatus.FAILED_PERMANENT)),
}

data class CallFilter(
    val classification: Classification? = null,
    val sync: SyncFilter? = null,
) {
    fun matches(entry: CallListEntry): Boolean =
        (classification == null || entry.call.classification == classification) &&
            (sync == null || entry.syncStatus in sync.statuses)

    val isActive: Boolean get() = classification != null || sync != null
}

data class CallListUiState(
    val loading: Boolean = true,
    val calls: List<CallListEntry> = emptyList(),
    val filter: CallFilter = CallFilter(),
    /** Across all calls, whatever the filter. */
    val waitingToUpload: Int = 0,
    val uploadFailed: Int = 0,
)

@HiltViewModel
class CallListViewModel @Inject constructor(
    calls: CallRepository,
    private val sweeps: SweepRequester,
    private val uploads: SyncRequester,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Enum names in SavedStateHandle: the filter survives process death.
    private val classificationFilter = savedState.getStateFlow<String?>(KEY_CLASSIFICATION, null)
    private val syncFilter = savedState.getStateFlow<String?>(KEY_SYNC, null)

    val state: StateFlow<CallListUiState> =
        combine(calls.observeCallList(), classificationFilter, syncFilter) { all, classification, sync ->
            val filter = CallFilter(
                classification = Classification.entries.firstOrNull { it.name == classification },
                sync = SyncFilter.entries.firstOrNull { it.name == sync },
            )
            CallListUiState(
                loading = false,
                calls = all.filter(filter::matches),
                filter = filter,
                waitingToUpload = all.count { it.syncStatus in SyncFilter.PENDING.statuses },
                uploadFailed = all.count { it.syncStatus in SyncFilter.FAILED.statuses },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallListUiState())

    /** "Upload now": doesn't wait for the debounce or the next periodic run. */
    fun uploadNow() = uploads.syncNow()

    fun setClassificationFilter(classification: Classification?) {
        savedState[KEY_CLASSIFICATION] = classification?.name
    }

    fun setSyncFilter(sync: SyncFilter?) {
        savedState[KEY_SYNC] = sync?.name
    }

    /** App open / permission just granted: pick up anything the background triggers missed. */
    fun refresh() = sweeps.requestSweep()

    private companion object {
        const val KEY_CLASSIFICATION = "filter_classification"
        const val KEY_SYNC = "filter_sync"
    }
}
