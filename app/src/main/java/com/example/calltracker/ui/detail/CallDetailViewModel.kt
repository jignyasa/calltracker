package com.example.calltracker.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.usecase.CreateLeadFromCallUseCase
import com.example.calltracker.domain.usecase.RetryUploadUseCase
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.domain.usecase.SetCallClassificationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val call: CallRecord? = null,
    val activity: Activity? = null,
    /** What the form shows; differs from [Activity.log] while the user has unsaved edits. */
    val draft: ActivityLog = ActivityLog(),
    /** Bumped when [draft] is replaced from the database, so the screen refills its fields only then. */
    val formVersion: Int = 0,
    val invalidFields: Set<SaveActivityLogUseCase.Field> = emptySet(),
    val saving: Boolean = false,
    val creatingLead: Boolean = false,
) {
    val canLog: Boolean get() = call?.classification == Classification.BUSINESS
    val dirty: Boolean get() = canLog && draft != (activity?.log ?: ActivityLog())
    val canCreateLead: Boolean get() = call?.normalizedNumber != null && call.crmMatch == null
    val canRetryUpload: Boolean get() = canLog && activity?.syncStatus == SyncStatus.FAILED_PERMANENT
}

sealed interface CallDetailEvent {
    data object Saved : CallDetailEvent
    data object NotBusiness : CallDetailEvent

    /** Marked personal, but its activity had already reached the CRM. */
    data object AlreadyUploaded : CallDetailEvent
    data class LeadCreated(val name: String) : CallDetailEvent
    data class LeadAlreadyInCrm(val name: String) : CallDetailEvent
    data object LeadUnavailable : CallDetailEvent
    data class LeadRejected(val reason: String) : CallDetailEvent
    data object LeadNameRequired : CallDetailEvent
    data object LeadNoNumber : CallDetailEvent
    data object RetryQueued : CallDetailEvent
}

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    calls: CallRepository,
    private val activities: ActivityRepository,
    private val setClassification: SetCallClassificationUseCase,
    private val saveLog: SaveActivityLogUseCase,
    private val createLead: CreateLeadFromCallUseCase,
    private val retry: RetryUploadUseCase,
) : ViewModel() {

    private val callId: Long = checkNotNull(savedState[ARG_CALL_ID]) { "call id missing" }

    private val _state = MutableStateFlow(CallDetailUiState())
    val state: StateFlow<CallDetailUiState> = _state.asStateFlow()

    private val _events = Channel<CallDetailEvent>(Channel.BUFFERED)
    val events: Flow<CallDetailEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(calls.observeCall(callId), activities.observeActivity(callId), ::Pair).collect { (call, activity) ->
                _state.update { current ->
                    val stored = activity?.log ?: ActivityLog()
                    // Take the stored log unless the user is mid-edit (never overwrite their typing).
                    val reload = !current.dirty && current.draft != stored
                    current.copy(
                        loading = false,
                        notFound = call == null,
                        call = call,
                        activity = activity,
                        draft = if (reload) stored else current.draft,
                        formVersion = if (reload || current.loading) current.formVersion + 1 else current.formVersion,
                    )
                }
            }
        }
    }

    fun onNotesChanged(text: String) = editDraft { copy(notes = text) }
    fun onNextActionChanged(text: String) = editDraft { copy(nextAction = text) }
    fun onRemarksChanged(text: String) = editDraft { copy(remarks = text) }
    fun onOutcomeChanged(outcome: CallOutcome?) = editDraft { copy(outcome = outcome) }
    fun onFollowUpChanged(epochMs: Long?) = editDraft { copy(followUpAt = epochMs) }

    private fun editDraft(change: ActivityLog.() -> ActivityLog) =
        _state.update { it.copy(draft = it.draft.change(), invalidFields = emptySet()) }

    fun discardChanges() = _state.update {
        it.copy(draft = it.activity?.log ?: ActivityLog(), formVersion = it.formVersion + 1, invalidFields = emptySet())
    }

    fun save() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saveLog(callId, _state.value.draft)
            // The stored log is the cleaned-up draft (trimmed, blanks dropped); show exactly that.
            val saved = if (result == SaveActivityLogUseCase.Result.Saved) activities.getActivity(callId) else null
            _state.update {
                it.copy(
                    saving = false,
                    invalidFields = (result as? SaveActivityLogUseCase.Result.Invalid)?.fields.orEmpty(),
                    activity = saved ?: it.activity,
                    draft = saved?.log ?: it.draft,
                    formVersion = if (saved != null) it.formVersion + 1 else it.formVersion,
                )
            }
            when (result) {
                SaveActivityLogUseCase.Result.Saved -> _events.send(CallDetailEvent.Saved)
                SaveActivityLogUseCase.Result.NotBusiness -> _events.send(CallDetailEvent.NotBusiness)
                SaveActivityLogUseCase.Result.CallNotFound, is SaveActivityLogUseCase.Result.Invalid -> Unit
            }
        }
    }

    fun retryUpload() {
        viewModelScope.launch { if (retry(callId)) _events.send(CallDetailEvent.RetryQueued) }
    }

    fun classify(classification: Classification, applyToNumber: Boolean) {
        val call = _state.value.call ?: return
        if (call.classification == classification && !applyToNumber) return
        viewModelScope.launch {
            val result = setClassification(callId, classification, applyToNumber)
            if (result is SetCallClassificationUseCase.Result.Success && result.alreadyUploaded) {
                _events.send(CallDetailEvent.AlreadyUploaded)
            }
        }
    }

    fun createLead(name: String, company: String?) {
        if (_state.value.creatingLead) return
        _state.update { it.copy(creatingLead = true) }
        viewModelScope.launch {
            val event = when (val result = createLead(callId, name, company)) {
                is CreateLeadFromCallUseCase.Result.Created -> CallDetailEvent.LeadCreated(result.lead.name)
                is CreateLeadFromCallUseCase.Result.AlreadyInCrm -> CallDetailEvent.LeadAlreadyInCrm(result.match.name)
                CreateLeadFromCallUseCase.Result.Unavailable -> CallDetailEvent.LeadUnavailable
                is CreateLeadFromCallUseCase.Result.Rejected -> CallDetailEvent.LeadRejected(result.reason)
                CreateLeadFromCallUseCase.Result.NameRequired -> CallDetailEvent.LeadNameRequired
                CreateLeadFromCallUseCase.Result.NoNumber, CreateLeadFromCallUseCase.Result.CallNotFound -> CallDetailEvent.LeadNoNumber
            }
            _state.update { it.copy(creatingLead = false) }
            _events.send(event)
        }
    }

    companion object {
        const val ARG_CALL_ID = "call_id"
    }
}
