package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.SweepWatermark

/** Thrown when READ_CALL_LOG has not been granted (or was revoked). */
class CallLogAccessDeniedException(cause: Throwable? = null) : Exception("READ_CALL_LOG not granted", cause)

interface CallLogSource {
    /**
     * Returns up to [limit] rows that are newer than [watermark] (by id, or by start time after [overlapMs]),
     * with id greater than [afterId], ordered by id ascending.
     *
     * @throws CallLogAccessDeniedException
     */
    suspend fun read(watermark: SweepWatermark, overlapMs: Long, afterId: Long, limit: Int): List<CallLogEntry>
}
