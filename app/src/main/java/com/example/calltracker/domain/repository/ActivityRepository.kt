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
}
