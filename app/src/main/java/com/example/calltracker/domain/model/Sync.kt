package com.example.calltracker.domain.model

/** One row of the sync outbox, as the uploader sees it. */
data class OutboxItem(
    val callId: Long,
    val clientId: String,
    val status: SyncStatus,
    /** Bumped on every (re)enqueue; a result only applies if it is still the generation that was sent. */
    val generation: Int,
    val attempts: Int,
    val lastError: String?,
)

/**
 * Exactly what leaves the phone for one BUSINESS call (mock-server/README.md "Activity fields").
 * Built only from the call and its activity; contact names and anything about other calls never go in here.
 */
data class ActivityPayload(
    val clientId: String,
    /** E.164. */
    val phone: String,
    /** INCOMING, OUTGOING, MISSED or REJECTED: the only types the CRM accepts. */
    val callType: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Long,
    val simSlot: Int?,
    val leadId: String?,
    val contactId: String?,
    val notes: String?,
    val outcome: CallOutcome?,
    val followUpAt: Long?,
    val nextAction: String?,
    val remarks: String?,
    val updatedAt: Long,
)

enum class UploadItemStatus { CREATED, DUPLICATE, UPDATED, CONFLICT, INVALID, ERROR }

/** The CRM's stored copy of an activity, returned with CONFLICT. */
data class ServerActivity(
    val log: ActivityLog,
    val updatedAt: Long,
)

data class UploadItemResult(
    val clientId: String,
    val status: UploadItemStatus,
    val serverId: String?,
    val version: Int?,
    val errors: List<String> = emptyList(),
    val server: ServerActivity? = null,
)

sealed interface UploadResult {
    /** The batch was processed; one result per item (an item missing from it is treated as transient). */
    data class Processed(val items: List<UploadItemResult>) : UploadResult

    /** Nothing was processed (network, 5xx, 429, auth); every item is retried later. */
    data class Failed(val reason: String) : UploadResult
}
