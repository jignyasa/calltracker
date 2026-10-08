package com.example.calltracker.domain.model

/**
 * One row from the device CallLog plus everything the app derived from it.
 *
 * @property callLogId `CallLog.Calls._ID`; with [startedAt] it identifies the source row (ids can be reused after a log wipe).
 * @property normalizedNumber E.164, or null for private/unknown/unparseable numbers.
 * @property deviceContactName `CACHED_NAME` from the CallLog. Local display only; never synced.
 */
data class CallRecord(
    val id: Long,
    val callLogId: Long,
    val rawNumber: String?,
    val normalizedNumber: String?,
    val deviceContactName: String?,
    val type: CallType,
    val startedAt: Long,
    val durationSec: Long,
    val phoneAccountId: String?,
    val simSlot: Int?,
    val classification: Classification,
    val classificationSource: ClassificationSource,
    val crmMatch: CrmMatch?,
) {
    val endedAt: Long get() = startedAt + durationSec * 1000
}
