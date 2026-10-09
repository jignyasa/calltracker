package com.example.calltracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.usecase.PostCallPromptPolicy
import com.example.calltracker.domain.usecase.ResolveCrmMatchesUseCase
import com.example.calltracker.ui.notifications.PostCallNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock

/**
 * Resolves CRM matches for recent calls, then (for a run right after a sweep) asks about the calls that are still
 * UNCLASSIFIED. Prompts come after the lookup so a caller who is in the CRM is never asked about.
 */
@HiltWorker
class CrmLookupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val resolve: ResolveCrmMatchesUseCase,
    private val calls: CallRepository,
    private val notifier: PostCallNotifier,
    private val scheduler: CrmLookupScheduler,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prompt = inputData.getBoolean(KEY_PROMPT, false)
        val result = resolve()
        Log.i(TAG, "Looked up ${result.lookedUp} numbers, reclassified ${result.reclassifiedCallIds.size} calls")

        // A call the CRM just decided no longer needs its prompt.
        for (id in result.reclassifiedCallIds) {
            if (calls.getCall(id)?.classification != Classification.UNCLASSIFIED) notifier.cancel(id)
        }
        // Prompt once: a retried run must not bring back a notification the user dismissed.
        if (prompt && runAttemptCount == 0) promptForUndecided()

        val morePending = result.unavailable || result.lookedUp >= ResolveCrmMatchesUseCase.MAX_LOOKUPS_PER_RUN
        return when {
            !morePending -> Result.success()
            // The post-sweep run never retries itself (it has no network constraint); hand off to the retry work.
            prompt -> Result.success().also { scheduler.scheduleRetry() }
            // This is the retry work: back off and go again (scheduleRetry() would be a no-op while it runs).
            else -> Result.retry()
        }
    }

    private suspend fun promptForUndecided() {
        val now = clock.millis()
        val ids = inputData.getLongArray(KEY_CALL_IDS) ?: return
        ids.toList()
            .mapNotNull { calls.getCall(it) }
            .filter { PostCallPromptPolicy.shouldPrompt(it, now) }
            .sortedByDescending { it.startedAt }
            .take(PostCallPromptPolicy.MAX_PROMPTS)
            .forEach { notifier.show(it) }
    }

    companion object {
        const val KEY_PROMPT = "prompt"
        const val KEY_CALL_IDS = "call_ids"
        private const val TAG = "CrmLookupWorker"
    }
}
