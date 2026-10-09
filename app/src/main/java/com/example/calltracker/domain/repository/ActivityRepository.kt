package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun observeActivity(callId: Long): Flow<Activity?>

    suspend fun getActivity(callId: Long): Activity?

    /** Returns the call's activity, creating it (with a fresh client UUID and an empty log) if missing. */
    suspend fun ensureActivity(callId: Long, now: Long): Activity

    suspend fun updateLog(callId: Long, log: ActivityLog, now: Long)

    /** Records the CRM's id and version after an accepted upload. */
    suspend fun setServerIdentity(callId: Long, serverId: String, version: Int?)

    /** Replaces the local log with the CRM's newer copy (conflict, last write wins). */
    suspend fun applyServerCopy(callId: Long, log: ActivityLog, updatedAt: Long, serverId: String?, version: Int?)
}
