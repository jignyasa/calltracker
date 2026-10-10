package com.example.calltracker.ui.queue

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.CallListQuery
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.SyncRequester
import com.example.calltracker.ui.calls.SyncFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SyncQueueUiState(
    val tab: SyncFilter = SyncFilter.PENDING,
    /** Across all calls: pending includes the ones being retried. */
    val pending: Int = 0,
    val failed: Int = 0,
)

/** The upload queue, one upload state per tab (Pending / Failed / Synced). Only business calls are ever in it. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SyncQueueViewModel @Inject constructor(
    callRepository: CallRepository,
    private val uploads: SyncRequester,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // The tab survives process death (enum name in SavedStateHandle).
    private val tab: Flow<SyncFilter> = savedState.getStateFlow(KEY_TAB, SyncFilter.PENDING.name)
        .map { name -> SyncFilter.entries.firstOrNull { it.name == name } ?: SyncFilter.PENDING }

    val calls: Flow<PagingData<CallListEntry>> = tab
        .flatMapLatest { callRepository.pagedCallList(CallListQuery(classification = null, syncStatuses = it.statuses)) }
        .cachedIn(viewModelScope)

    val state: StateFlow<SyncQueueUiState> =
        combine(tab, callRepository.observeUploadCounts()) { tab, counts ->
            SyncQueueUiState(tab, counts.waiting, counts.failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncQueueUiState())

    fun selectTab(tab: SyncFilter) {
        savedState[KEY_TAB] = tab.name
    }

    /** "Upload now": doesn't wait for the debounce or the next periodic run. */
    fun uploadNow() = uploads.syncNow()

    private companion object {
        const val KEY_TAB = "queue_tab"
    }
}
