package com.example.calltracker.domain.model

/** A call as shown in the list: the call plus its activity's outbox state (null when nothing is queued). */
data class CallListEntry(
    val call: CallRecord,
    val syncStatus: SyncStatus?,
)

/** A SIM currently in the phone. [slot] is 0-based, as stored on calls. */
data class SimCard(
    val slot: Int,
    val label: String,
)
