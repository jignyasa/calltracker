package com.example.calltracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.calltracker.domain.repository.SyncRequester
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules [SyncWorker]. Always unique work, always waiting for a network connection. */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncRequester {

    private val workManager get() = WorkManager.getInstance(context)

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Something was queued. The short delay lets the enqueuing transaction commit and lets a burst of edits share one
     * run. APPEND_OR_REPLACE: a run already uploading finishes, then one more picks up what arrived meanwhile.
     */
    override fun requestSync() = enqueue(delaySeconds = DEBOUNCE_SECONDS)

    /** "Upload now" from the UI. */
    override fun syncNow() = enqueue(delaySeconds = 0)

    private fun enqueue(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net: anything left behind (a run killed by the OS, a missed trigger) goes out within the hour. */
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        // App start / boot: flush whatever was queued before (a no-op run when the outbox is empty).
        requestSync()
    }

    companion object {
        const val TAG = "activity-sync"
        const val ONE_SHOT = "activity-sync-oneshot"
        const val PERIODIC = "activity-sync-periodic"
        const val DEBOUNCE_SECONDS = 5L
        const val BACKOFF_SECONDS = 30L
        const val PERIODIC_HOURS = 1L
    }
}
