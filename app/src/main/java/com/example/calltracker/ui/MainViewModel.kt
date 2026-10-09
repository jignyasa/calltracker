package com.example.calltracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.usecase.SetCallClassificationUseCase
import com.example.calltracker.ui.notifications.PostCallNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val setClassification: SetCallClassificationUseCase,
    private val notifier: PostCallNotifier,
) : ViewModel() {

    /** "Log" on the post-call notification: logging details means it's a work call. The form arrives in Phase 5. */
    fun onLogCallRequested(callId: Long) {
        notifier.cancel(callId)
        viewModelScope.launch { setClassification(callId, Classification.BUSINESS, applyToNumber = false) }
    }
}
