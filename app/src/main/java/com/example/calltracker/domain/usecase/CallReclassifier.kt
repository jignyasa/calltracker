package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.repository.CallRepository
import javax.inject.Inject

/**
 * Re-runs the rule engine for one stored call after a signal changed (CRM answer, rule, work SIM).
 * Calls the user classified by hand are never touched. Callers run it inside a
 * [com.example.calltracker.domain.repository.TransactionRunner] block: it reconciles the outbox for a changed call.
 */
class CallReclassifier @Inject constructor(
    private val calls: CallRepository,
    private val gatherSignals: GatherClassificationSignals,
    private val classify: ClassifyCallUseCase,
    private val syncGate: SyncGate,
) {
    /** @return true if the call's classification changed. */
    suspend fun reclassify(call: CallRecord, workSimSlot: Int?): Boolean {
        if (call.classificationSource == ClassificationSource.USER_MANUAL) return false
        val signals = gatherSignals(call.normalizedNumber, call.simSlot, workSimSlot)
        val decision = classify(signals)
        if (decision.classification == call.classification && decision.source == call.classificationSource) return false

        calls.setClassification(call.id, decision.classification, decision.source)
        syncGate.reconcile(call.id)
        return true
    }
}
