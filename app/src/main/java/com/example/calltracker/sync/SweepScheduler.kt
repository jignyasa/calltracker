package com.example.calltracker.sync

import android.content.Context
import android.provider.CallLog
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every way a CallLog sweep gets triggered. All of them use unique work, so a burst of triggers
 * (e.g. PHONE_STATE fired twice, plus the content trigger) collapses into at most one running and one queued sweep.
 */
/** What screens need from the scheduler; an interface so ViewModels stay JVM-testable. */
interface SweepRequester {
    fun requestSweep()
}

@Singleton
class SweepScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SweepRequester {
    private val workManager get() = WorkManager.getInstance(context)

    override fun requestSweep() = requestSweep(delaySeconds = 0)

    /** One-shot sweep. APPEND_OR_REPLACE: a sweep already running finishes, then another runs to catch rows it missed. */
    fun requestSweep(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<CallLogSweepWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net for every missed trigger (OEM-killed receivers, Doze). */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<CallLogSweepWorker>(
            PERIODIC_HOURS, TimeUnit.HOURS,
            PERIODIC_FLEX_MINUTES, TimeUnit.MINUTES,
        ).addTag(TAG).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * JobScheduler content trigger on the CallLog: wakes the app when a call is written even if the process is dead
     * and the PHONE_STATE broadcast never arrived. Content-trigger jobs fire once, so the sweep worker re-arms it.
     */
    fun armCallLogTrigger(replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<CallLogChangedWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                    .build(),
            )
            .addTag(TAG)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(CONTENT_TRIGGER, policy, request)
    }

    /** Called on app start and boot; cheap and idempotent. */
    fun ensureScheduled() {
        schedulePeriodic()
        armCallLogTrigger(replace = false)
    }

    companion object {
        const val TAG = "calllog-sweep"
        const val ONE_SHOT = "calllog-sweep-oneshot"
        const val PERIODIC = "calllog-sweep-periodic"
        const val CONTENT_TRIGGER = "calllog-content-trigger"
        const val PERIODIC_HOURS = 1L
        const val PERIODIC_FLEX_MINUTES = 20L

        /** Give the dialer time to write the CallLog row after the call ends. */
        const val AFTER_CALL_DELAY_SECONDS = 3L
    }
}
