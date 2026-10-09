package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.ActivityDao
import com.example.calltracker.data.local.entity.ActivityEntity
import com.example.calltracker.data.local.toDomain
import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class ActivityRepositoryImpl @Inject constructor(
    private val dao: ActivityDao,
) : ActivityRepository {

    override fun observeActivity(callId: Long): Flow<Activity?> = dao.observe(callId).map { it?.toDomain() }

    override suspend fun getActivity(callId: Long): Activity? = dao.get(callId)?.toDomain()

    override suspend fun ensureActivity(callId: Long, now: Long): Activity {
        dao.get(callId)?.let { return it.toDomain() }
        dao.insertIgnore(
            ActivityEntity(
                callId = callId,
                clientId = UUID.randomUUID().toString(),
                notes = null,
                outcome = null,
                followUpAt = null,
                nextAction = null,
                remarks = null,
                updatedAt = now,
                serverId = null,
                serverVersion = null,
            ),
        )
        return checkNotNull(dao.get(callId)) { "Activity for call $callId missing after insert" }.toDomain()
    }

    override suspend fun updateLog(callId: Long, log: ActivityLog, now: Long) {
        val rows = dao.updateLog(callId, log.notes, log.outcome, log.followUpAt, log.nextAction, log.remarks, now)
        check(rows == 1) { "No activity for call $callId" }
    }

    override suspend fun setServerIdentity(callId: Long, serverId: String, version: Int?) =
        dao.setServerIdentity(callId, serverId, version)

    override suspend fun applyServerCopy(callId: Long, log: ActivityLog, updatedAt: Long, serverId: String?, version: Int?) =
        dao.applyServerCopy(callId, log.notes, log.outcome, log.followUpAt, log.nextAction, log.remarks, updatedAt, serverId, version)
}
