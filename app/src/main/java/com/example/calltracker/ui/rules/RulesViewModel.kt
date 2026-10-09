package com.example.calltracker.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.NumberRule
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.usecase.DeleteNumberRuleUseCase
import com.example.calltracker.domain.usecase.SetNumberRuleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RulesUiState(
    val loading: Boolean = true,
    val rules: List<NumberRule> = emptyList(),
)

sealed interface RulesEvent {
    data class Saved(val updatedCalls: Int) : RulesEvent
    data class Deleted(val updatedCalls: Int) : RulesEvent
    data object InvalidNumber : RulesEvent
}

@HiltViewModel
class RulesViewModel @Inject constructor(
    rules: NumberRuleRepository,
    private val setRule: SetNumberRuleUseCase,
    private val deleteRule: DeleteNumberRuleUseCase,
) : ViewModel() {

    val state: StateFlow<RulesUiState> = rules.observeRules()
        .map { RulesUiState(loading = false, rules = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    private val _events = Channel<RulesEvent>(Channel.BUFFERED)
    val events: Flow<RulesEvent> = _events.receiveAsFlow()

    fun addRule(number: String, classification: Classification) {
        viewModelScope.launch {
            _events.send(
                when (val result = setRule(number, classification)) {
                    is SetNumberRuleUseCase.Result.Saved -> RulesEvent.Saved(result.reclassifiedCalls)
                    SetNumberRuleUseCase.Result.InvalidNumber -> RulesEvent.InvalidNumber
                },
            )
        }
    }

    fun switch(rule: NumberRule) {
        val other = if (rule.classification == Classification.BUSINESS) Classification.PERSONAL else Classification.BUSINESS
        addRule(rule.normalizedNumber, other)
    }

    fun delete(rule: NumberRule) {
        viewModelScope.launch { _events.send(RulesEvent.Deleted(deleteRule(rule.normalizedNumber))) }
    }
}
