package com.example.calltracker.domain.model

/** Normalised from `CallLog.Calls.TYPE`. BLOCKED/VOICEMAIL/OTHER are kept locally but never synced as such. */
enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED,
    REJECTED,
    BLOCKED,
    VOICEMAIL,
    OTHER,
}
