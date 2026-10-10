package com.example.calltracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.calltracker.domain.repository.CrmLookupRequester
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrmLookupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : CrmLookupRequester {
    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Right after a sweep imported calls: look them up and then prompt for the ones still undecided.
     * No network constraint, so the prompt isn't held back offline; lookups that fail go to [scheduleRetry].
     */
    fun resolveAfterSweep(importedCallIds: List<Long>) {
        val request = OneTimeWorkRequestBuilder<CrmLookupWorker>()
            .setInputData(
                workDataOf(
                    CrmLookupWorker.KEY_PROMPT to true,
                    // Only the newest calls can still be prompted for; this also keeps the input small.
                    CrmLookupWorker.KEY_CALL_IDS to importedCallIds.takeLast(MAX_PROMPT_CANDIDATES).toLongArray(),
                ),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(AFTER_SWEEP, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Lookups still pending (offline, CRM down, or more numbers than one run handles): retry once online.
     * REPLACE, not KEEP: after a long outage the old retry may be in exponential backoff for many minutes; a new call
     * is a reason to try again soon, so the cadence restarts at [BACKOFF_SECONDS]. Lookups are idempotent, so
     * replacing a run in progress is harmless.
     */
    fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<CrmLookupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    /** A lookup outside a sweep (e.g. a CRM link just went stale): the network-bound run, which needs no call ids. */
    override fun requestLookup() = scheduleRetry()

    companion object {
        const val TAG = "crm-lookup"
        const val AFTER_SWEEP = "crm-lookup-after-sweep"
        const val RETRY = "crm-lookup-retry"
        const val MAX_PROMPT_CANDIDATES = 50
        const val BACKOFF_SECONDS = 30L
    }
}
