package com.example.calltracker.testing

import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.repository.CallLogAccessDeniedException
import com.example.calltracker.domain.repository.CallLogSource

/** In-memory CallLog applying the same selection as `AndroidCallLogSource`. */
class FakeCallLogSource : CallLogSource {

    val rows = mutableListOf<CallLogEntry>()
    var permissionGranted = true
    val requests = mutableListOf<SweepWatermark>()

    fun add(
        id: Long,
        startedAt: Long,
        number: String? = "9876543210",
        cachedName: String? = null,
        type: CallType = CallType.INCOMING,
        durationSec: Long = 30,
        simSlot: Int? = null,
    ) {
        rows += CallLogEntry(id, number, cachedName, type, startedAt, durationSec, phoneAccountId = null, simSlot = simSlot)
    }

    override suspend fun read(watermark: SweepWatermark, overlapMs: Long, afterId: Long, limit: Int): List<CallLogEntry> {
        if (!permissionGranted) throw CallLogAccessDeniedException()
        requests += watermark
        return rows
            .filter {
                (it.callLogId > watermark.lastCallLogId || it.startedAt > watermark.lastStartedAt - overlapMs) &&
                    it.startedAt >= watermark.notBefore && it.callLogId > afterId
            }
            .sortedBy { it.callLogId }
            .take(limit)
    }
}
