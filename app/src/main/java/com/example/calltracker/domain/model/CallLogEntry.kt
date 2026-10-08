package com.example.calltracker.domain.model

/** A raw row read from the device CallLog, before normalisation and classification. */
data class CallLogEntry(
    val callLogId: Long,
    val rawNumber: String?,
    /** Contact name the dialer cached for this number; non-null means it's a saved device contact. */
    val cachedName: String?,
    val type: CallType,
    val startedAt: Long,
    val durationSec: Long,
    val phoneAccountId: String?,
    /** 0-based SIM slot when it could be resolved from [phoneAccountId]. */
    val simSlot: Int?,
)

/**
 * Where the CallLog sweep has got to. Rows newer by id *or* by date than this are read again;
 * the date check (with a small overlap) catches id resets after the user clears the log, the id check catches
 * long calls that are written to the log after shorter, later-starting ones.
 */
data class SweepWatermark(
    val lastCallLogId: Long,
    val lastStartedAt: Long,
)
