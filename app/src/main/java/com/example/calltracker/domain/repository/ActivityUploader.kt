package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.UploadResult

/** Sends one batch (≤ [MAX_BATCH] items) to the CRM. Each item's clientId is its idempotency key. */
interface ActivityUploader {
    suspend fun upload(items: List<ActivityPayload>): UploadResult

    companion object {
        const val MAX_BATCH = 100
    }
}

/** Asks for an upload run soon (after the current transaction has committed). */
interface SyncRequester {
    fun requestSync()

    /** The user asked: no debounce delay. */
    fun syncNow() = requestSync()
}
