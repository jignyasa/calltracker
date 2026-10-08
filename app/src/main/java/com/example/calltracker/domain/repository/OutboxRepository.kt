package com.example.calltracker.domain.repository

/**
 * The sync outbox. Only [com.example.calltracker.domain.usecase.SyncGate] may call [enqueue];
 * that's where the BUSINESS-only rule is enforced.
 */
interface OutboxRepository {
    /** Inserts or re-arms (back to PENDING, new generation) the outbox item for [callId]'s activity. */
    suspend fun enqueue(callId: Long, clientId: String, now: Long)

    /** Drops the item unless it is already SYNCED. */
    suspend fun removeUnsynced(callId: Long)
}
