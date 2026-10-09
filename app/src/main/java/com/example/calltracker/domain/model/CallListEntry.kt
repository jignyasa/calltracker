package com.example.calltracker.domain.model

/** A call as shown in the list: the call plus its activity's outbox state (null when nothing is queued). */
data class CallListEntry(
    val call: CallRecord,
    val syncStatus: SyncStatus?,
)

/** What the call list shows, filtered in the database. Null means "any". */
data class CallListQuery(
    val classification: Classification? = null,
    val syncStatuses: Set<SyncStatus>? = null,
)

/** Across all calls. [waiting] includes in-flight and retryable items; [failed] needs the user. */
data class UploadCounts(
    val waiting: Int = 0,
    val failed: Int = 0,
)

/** A SIM currently in the phone. [slot] is 0-based, as stored on calls. */
data class SimCard(
    val slot: Int,
    val label: String,
)
