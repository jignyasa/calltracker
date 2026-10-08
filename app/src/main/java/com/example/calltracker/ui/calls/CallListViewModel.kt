package com.example.calltracker.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.sync.SweepScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CallListUiState(
    val loading: Boolean = true,
    val calls: List<CallRecord> = emptyList(),
)

@HiltViewModel
class CallListViewModel @Inject constructor(
    calls: CallRepository,
    private val sweepScheduler: SweepScheduler,
) : ViewModel() {

    val state: StateFlow<CallListUiState> = calls.observeCalls()
        .map { CallListUiState(loading = false, calls = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallListUiState())

    /** App open / permission just granted: pick up anything the background triggers missed. */
    fun refresh() = sweepScheduler.requestSweep()
}
