package com.example.calltracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.usecase.PostCallPromptPolicy
import com.example.calltracker.domain.usecase.ResolveCrmMatchesUseCase
import com.example.calltracker.ui.notifications.PostCallNotifier
import com.example.calltracker.ui.postcall.PostCallLogOffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock

/**
 * Looks up recent professional numbers in the CRM (links their calls, or creates the lead), then acts on the calls
 * that just ended: asks "Work call?" about the ones still UNCLASSIFIED (numbers with no saved answer), and offers the
 * log form (pop-up) for the newest BUSINESS one. The lookup doesn't decide classification: unsorted and personal
 * numbers are never sent to the CRM.
 */
@HiltWorker
class CrmLookupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val resolve: ResolveCrmMatchesUseCase,
    private val calls: CallRepository,
    private val notifier: PostCallNotifier,
    private val logOffer: PostCallLogOffer,
    private val scheduler: CrmLookupScheduler,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prompt = inputData.getBoolean(KEY_PROMPT, false)
        val result = resolve()
        Log.i(TAG, "Looked up ${result.lookedUp} numbers, reclassified ${result.reclassifiedCallIds.size} calls, created ${result.leadsCreated} leads")

        // A call the CRM just decided no longer needs its prompt.
        for (id in result.reclassifiedCallIds) {
            if (calls.getCall(id)?.classification != Classification.UNCLASSIFIED) notifier.cancel(id)
        }
        // Once only: a retried run must not bring back a prompt or pop-up the user already dealt with.
        if (runAttemptCount == 0) {
            val now = clock.millis()
            val fresh = if (prompt) inputData.getLongArray(KEY_CALL_IDS)?.toList().orEmpty() else emptyList()
            val candidates = (fresh + result.reclassifiedCallIds).distinct().mapNotNull { calls.getCall(it) }
            if (prompt) promptForUndecided(candidates, now)
            offerLogging(candidates, now)
        }

        val morePending = result.unavailable || result.lookedUp >= ResolveCrmMatchesUseCase.MAX_LOOKUPS_PER_RUN
        return when {
            !morePending -> Result.success()
            // The post-sweep run never retries itself (it has no network constraint); hand off to the retry work.
            prompt -> Result.success().also { scheduler.scheduleRetry() }
            // This is the retry work itself: back off and go again.
            else -> Result.retry()
        }
    }

    /**
     * "Work call?" for calls the rules couldn't decide. At most [PostCallPromptPolicy.MAX_PROMPTS] show at once, across
     * runs: the newest calls keep a prompt, an older one gives way (its call still waits under Unsorted in the app).
     */
    private suspend fun promptForUndecided(candidates: List<CallRecord>, now: Long) {
        val fresh = candidates.filter { PostCallPromptPolicy.shouldPrompt(it, now) }
        if (fresh.isEmpty()) return
        val showing = notifier.shownPromptCallIds().mapNotNull { calls.getCall(it) }
        val keep = (showing + fresh)
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
            .take(PostCallPromptPolicy.MAX_PROMPTS)
            .map { it.id }
            .toSet()
        showing.filter { it.id !in keep }.forEach { notifier.cancel(it.id) }
        fresh.filter { it.id in keep && showing.none { shown -> shown.id == it.id } }.forEach { notifier.show(it) }
    }

    /**
     * The log form for the newest business call that just ended — new from this sweep, or just turned BUSINESS by
     * the CRM lookup (e.g. after an outage). One at a time: the others wait in the list.
     */
    private fun offerLogging(candidates: List<CallRecord>, now: Long) {
        candidates
            .filter { PostCallPromptPolicy.shouldOfferLogging(it, now) }
            .maxByOrNull { it.startedAt }
            ?.let(logOffer::offer)
    }

    companion object {
        const val KEY_PROMPT = "prompt"
        const val KEY_CALL_IDS = "call_ids"
        private const val TAG = "CrmLookupWorker"
    }
}
