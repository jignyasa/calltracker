package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.OutboxItem

/**
 * The sync outbox. Only [com.example.calltracker.domain.usecase.SyncGate] may call [enqueue];
 * that's where the BUSINESS-only rule is enforced. The other methods belong to the uploader.
 */
interface OutboxRepository {
    /** Inserts or re-arms (back to PENDING, new generation) the outbox item for [callId]'s activity. */
    suspend fun enqueue(callId: Long, clientId: String, now: Long)

    /** Drops the item unless it is already SYNCED. */
    suspend fun removeUnsynced(callId: Long)

    /** Crash recovery: items left IN_FLIGHT by a run that died go back to PENDING. @return how many. */
    suspend fun resetInFlight(now: Long): Int

    /**
     * Marks up to [limit] uploadable items (PENDING or FAILED_RETRYABLE, oldest first, not in [exclude]) IN_FLIGHT
     * and returns them as claimed.
     */
    suspend fun claimBatch(limit: Int, exclude: Set<Long>, now: Long): List<OutboxItem>

    /** SYNCED, but only if [generation] is still current. @return whether it applied. */
    suspend fun markSynced(callId: Long, generation: Int, now: Long): Boolean

    /** SYNCED whatever the generation: the server's newer copy replaced the local edit. */
    suspend fun forceSynced(callId: Long, now: Long)

    /**
     * FAILED_PERMANENT or FAILED_RETRYABLE with [error] and one more attempt, only if [generation] is still current
     * (otherwise the user has edited it since, and the new version is already PENDING). @return whether it applied.
     */
    suspend fun markFailed(callId: Long, generation: Int, permanent: Boolean, error: String, now: Long): Boolean
}
