package com.example.calltracker.domain.model

enum class SyncStatus {
    PENDING,
    IN_FLIGHT,
    SYNCED,
    FAILED_RETRYABLE,

    /** The server rejected the item (validation); retrying unchanged would fail again. */
    FAILED_PERMANENT,
}
