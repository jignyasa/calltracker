package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * Re-runs the rule engine for every call from a number after one of its signals changed (a fresh CRM answer, a rule
 * added or removed). Also refreshes the CRM record shown on those calls. See [CallReclassifier].
 *
 * When the CRM record a BUSINESS call points to changes (a lead was created for it, or the lookup found the number
 * after the call was uploaded), its activity is re-sent so the CRM copy gets linked too. The activity's timestamp
 * moves forward, or the CRM would treat the re-send as an older copy.
 */
class ReclassifyNumberUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val settings: SettingsRepository,
    private val reclassifier: CallReclassifier,
    private val activities: ActivityRepository,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    /** @return ids of the calls whose classification changed. */
    suspend operator fun invoke(normalizedNumber: String): Set<Long> {
        val workSimSlot = settings.workSimSlot()
        return transaction.run {
            val linkBefore = calls.getCallsForNumber(normalizedNumber).associate { it.id to it.crmLink() }
            calls.setCrmMatch(normalizedNumber, crm.cached(normalizedNumber)?.match)
            val reclassified = calls.getCallsForNumber(normalizedNumber)
                .filter { reclassifier.reclassify(it, workSimSlot) }
                .map { it.id }
                .toSet()
            calls.getCallsForNumber(normalizedNumber)
                .filter { it.classification == Classification.BUSINESS && it.crmLink() != linkBefore[it.id] }
                .forEach { resendWithNewLink(it.id) }
            reclassified
        }
    }

    private suspend fun resendWithNewLink(callId: Long) {
        val activity = activities.getActivity(callId) ?: return // not queued yet: its first upload carries the link
        activities.updateLog(callId, activity.log, maxOf(clock.millis(), activity.updatedAt + 1))
        syncGate.reconcile(callId, contentChanged = true)
    }

    /** What the upload sends about the CRM record; a renamed lead isn't a new link. */
    private fun CallRecord.crmLink() = crmMatch?.let { it.type to it.id }
}
