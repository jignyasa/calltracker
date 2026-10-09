package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.TransactionRunner
import javax.inject.Inject

/**
 * Re-runs the rule engine for every call from a number after one of its signals changed (usually a fresh CRM answer).
 * Calls the user classified by hand are left alone. Each changed call is reconciled with the outbox in the same
 * transaction, so a call that turns BUSINESS is queued and one that stops being BUSINESS is dequeued.
 */
class ReclassifyNumberUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val settings: SettingsRepository,
    private val gatherSignals: GatherClassificationSignals,
    private val classify: ClassifyCallUseCase,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
) {
    /** @return ids of the calls whose classification changed. */
    suspend operator fun invoke(normalizedNumber: String): Set<Long> {
        val workSimSlot = settings.workSimSlot()
        return transaction.run {
            calls.setCrmMatch(normalizedNumber, crm.cached(normalizedNumber)?.match)

            val changed = mutableSetOf<Long>()
            for (call in calls.getCallsForNumber(normalizedNumber)) {
                if (call.classificationSource == ClassificationSource.USER_MANUAL) continue
                val signals = gatherSignals(normalizedNumber, call.simSlot, call.deviceContactName != null, workSimSlot)
                val decision = classify(signals)
                if (decision.classification == call.classification && decision.source == call.classificationSource) continue

                calls.setClassification(call.id, decision.classification, decision.source)
                syncGate.reconcile(call.id)
                changed += call.id
            }
            changed
        }
    }
}
