package com.example.calltracker.domain.model

/**
 * The CRM activity for a BUSINESS call. Created as soon as the call is classified BUSINESS (log fields empty)
 * and updated when the user fills the post-call form.
 *
 * @property clientId UUID generated once on the device; the server's idempotency key for this activity.
 * @property updatedAt last local edit (epoch ms); the server uses it to resolve conflicting edits.
 */
data class Activity(
    val callId: Long,
    val clientId: String,
    val log: ActivityLog,
    val updatedAt: Long,
    val serverId: String?,
    /** Null when the activity isn't in the outbox (never queued, or dequeued after becoming personal). */
    val syncStatus: SyncStatus?,
    val lastSyncError: String?,
)

/** User-entered post-call details. All optional. */
data class ActivityLog(
    val notes: String? = null,
    val outcome: CallOutcome? = null,
    val followUpAt: Long? = null,
    val nextAction: String? = null,
    val remarks: String? = null,
) {
    companion object {
        const val MAX_TEXT_LENGTH = 5000
    }
}
