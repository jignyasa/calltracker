package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.repository.TransactionRunner
import javax.inject.Inject

/**
 * "Retry upload" on a call the CRM rejected: queues it again as a new generation (still only if it is BUSINESS).
 * Useful after the user fixed the problem, or after the CRM side was fixed.
 */
class RetryUploadUseCase @Inject constructor(
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
) {
    /** @return true if it was queued. */
    suspend operator fun invoke(callId: Long): Boolean = transaction.run { syncGate.reconcile(callId, contentChanged = true) }
}
