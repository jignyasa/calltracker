package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.OutboxDao
import com.example.calltracker.domain.repository.OutboxRepository
import javax.inject.Inject

class OutboxRepositoryImpl @Inject constructor(
    private val dao: OutboxDao,
) : OutboxRepository {

    override suspend fun enqueue(callId: Long, clientId: String, now: Long) = dao.enqueue(callId, clientId, now)

    override suspend fun removeUnsynced(callId: Long) {
        dao.deleteUnsynced(callId)
    }
}
