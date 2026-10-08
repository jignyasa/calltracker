package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.OutboxRepository
import java.time.Clock
import javax.inject.Inject

/**
 * The single path into the sync outbox, and therefore the privacy boundary:
 * only calls currently classified BUSINESS are ever enqueued.
 *
 * Callers run it inside a [com.example.calltracker.domain.repository.TransactionRunner] together with the
 * change that triggered it, so classification and outbox can never disagree.
 */
class SyncGate @Inject constructor(
    private val calls: CallRepository,
    private val activities: ActivityRepository,
    private val outbox: OutboxRepository,
    private val clock: Clock,
) {

    /**
     * Brings the outbox in line with the call's current classification.
     *
     * @param contentChanged the activity was just edited, so it must be (re)sent even if already synced.
     * @return true if the call's activity is in the outbox afterwards.
     */
    suspend fun reconcile(callId: Long, contentChanged: Boolean = false): Boolean {
        val call = calls.getCall(callId) ?: return false
        if (call.classification != Classification.BUSINESS) {
            outbox.removeUnsynced(callId)
            return false
        }

        val now = clock.millis()
        val existing = activities.getActivity(callId)
        val activity = existing ?: activities.ensureActivity(callId, now)
        if (existing == null || existing.syncStatus == null || contentChanged) {
            outbox.enqueue(callId, activity.clientId, now)
        }
        return true
    }
}
