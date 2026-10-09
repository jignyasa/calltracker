package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.OutboxDao
import com.example.calltracker.data.local.entity.OutboxEntity
import com.example.calltracker.domain.model.OutboxItem
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.OutboxRepository
import com.example.calltracker.domain.repository.SyncRequester
import javax.inject.Inject

class OutboxRepositoryImpl @Inject constructor(
    private val dao: OutboxDao,
    private val sync: SyncRequester,
) : OutboxRepository {

    override suspend fun enqueue(callId: Long, clientId: String, now: Long) {
        dao.enqueue(callId, clientId, now)
        // Runs inside the caller's transaction; the sync work starts with a short delay, after it has committed.
        sync.requestSync()
    }

    override suspend fun removeUnsynced(callId: Long) {
        dao.deleteUnsynced(callId)
    }

    override suspend fun resetInFlight(now: Long): Int = dao.resetInFlight(now)

    override suspend fun claimBatch(limit: Int, exclude: Set<Long>, now: Long): List<OutboxItem> =
        dao.claim(limit, exclude.toList(), now).map { it.toDomain() }

    override suspend fun markSynced(callId: Long, generation: Int, now: Long): Boolean =
        dao.markSynced(callId, generation, now) == 1

    override suspend fun forceSynced(callId: Long, now: Long) = dao.forceSynced(callId, now)

    override suspend fun markFailed(callId: Long, generation: Int, permanent: Boolean, error: String, now: Long): Boolean {
        val status = if (permanent) SyncStatus.FAILED_PERMANENT else SyncStatus.FAILED_RETRYABLE
        return dao.markFailed(callId, generation, status, error.take(MAX_ERROR_LENGTH), now) == 1
    }

    private fun OutboxEntity.toDomain() = OutboxItem(callId, clientId, status, generation, attempts, lastError)

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}
