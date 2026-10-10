package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.OutboxItem
import com.example.calltracker.domain.model.UploadItemResult
import com.example.calltracker.domain.model.UploadItemStatus
import com.example.calltracker.domain.model.UploadResult
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.ActivityUploader
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmLookupRequester
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.OutboxRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * Uploads the outbox in batches. Each item's result is applied on its own (one bad item never blocks the batch),
 * and only if the item's generation is unchanged since it was sent, so an edit made during an upload is re-sent
 * instead of being marked done.
 */
class UploadPendingActivitiesUseCase @Inject constructor(
    private val outbox: OutboxRepository,
    private val calls: CallRepository,
    private val activities: ActivityRepository,
    private val uploader: ActivityUploader,
    private val crm: CrmRepository,
    private val crmLookups: CrmLookupRequester,
    private val reclassifyNumber: ReclassifyNumberUseCase,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {

    data class Result(
        val uploaded: Int,
        val failedPermanently: Int,
        /** Something is left that a later run should retry (transient batch or item failure). */
        val retryNeeded: Boolean,
    )

    suspend operator fun invoke(): Result {
        outbox.resetInFlight(clock.millis())
        val attempted = mutableSetOf<Long>()
        var uploaded = 0
        var failed = 0
        var retry = false

        while (attempted.size < MAX_ITEMS_PER_RUN) {
            val batch = transaction.run { outbox.claimBatch(ActivityUploader.MAX_BATCH, attempted, clock.millis()) }
            if (batch.isEmpty()) break
            attempted += batch.map { it.callId }

            val sendable = mutableListOf<Pair<OutboxItem, ActivityPayload>>()
            for (item in batch) {
                when (val prepared = prepare(item)) {
                    is Prepared.Send -> sendable += item to prepared.payload
                    is Prepared.Drop -> Unit
                    is Prepared.Reject -> {
                        outbox.markFailed(item.callId, item.generation, permanent = true, error = prepared.reason, now = clock.millis())
                        failed++
                    }
                }
            }
            if (sendable.isEmpty()) continue

            when (val result = uploader.upload(sendable.map { it.second })) {
                is UploadResult.Failed -> {
                    transaction.run {
                        sendable.forEach { (item, _) ->
                            outbox.markFailed(item.callId, item.generation, permanent = false, error = result.reason, now = clock.millis())
                        }
                    }
                    // The CRM is unreachable; don't hammer it with the rest of the queue.
                    return Result(uploaded, failed, retryNeeded = true)
                }
                is UploadResult.Processed -> {
                    val byClientId = result.items.associateBy { it.clientId.lowercase() }
                    for ((item, payload) in sendable) {
                        when (apply(item, payload, byClientId[item.clientId.lowercase()])) {
                            Applied.UPLOADED -> uploaded++
                            Applied.FAILED -> failed++
                            Applied.RETRY -> retry = true
                            Applied.SUPERSEDED -> Unit
                        }
                    }
                }
            }
        }
        // Items enqueued during this run were excluded (attempted set / fresh claims); the run they triggered picks them up.
        return Result(uploaded, failed, retry || attempted.size >= MAX_ITEMS_PER_RUN)
    }

    private sealed interface Prepared {
        data class Send(val payload: ActivityPayload) : Prepared

        /** Not BUSINESS (any more): taken out of the outbox, never sent. */
        data object Drop : Prepared
        data class Reject(val reason: String) : Prepared
    }

    private suspend fun prepare(item: OutboxItem): Prepared = transaction.run {
        val call = calls.getCall(item.callId)
        val activity = activities.getActivity(item.callId)
        // Privacy, second line of defence after SyncGate: only a call that is BUSINESS right now is ever sent.
        if (call == null || activity == null || call.classification != Classification.BUSINESS) {
            outbox.removeUnsynced(item.callId)
            return@run Prepared.Drop
        }
        val phone = call.normalizedNumber ?: return@run Prepared.Reject(NO_NUMBER)
        Prepared.Send(payload(call, activity, phone))
    }

    private fun payload(call: CallRecord, activity: Activity, phone: String) = ActivityPayload(
        clientId = activity.clientId,
        phone = phone,
        callType = crmCallType(call.type),
        startedAt = call.startedAt,
        endedAt = call.endedAt,
        durationSec = call.durationSec,
        simSlot = call.simSlot,
        leadId = call.crmMatch?.takeIf { it.type == CrmRecordType.LEAD }?.id,
        contactId = call.crmMatch?.takeIf { it.type == CrmRecordType.CONTACT }?.id,
        notes = activity.log.notes,
        outcome = activity.log.outcome,
        followUpAt = activity.log.followUpAt,
        nextAction = activity.log.nextAction,
        remarks = activity.log.remarks,
        updatedAt = activity.updatedAt,
    )

    private enum class Applied { UPLOADED, FAILED, RETRY, SUPERSEDED }

    private suspend fun apply(item: OutboxItem, sent: ActivityPayload, result: UploadItemResult?): Applied = transaction.run {
        val now = clock.millis()
        when (result?.status) {
            null -> {
                outbox.markFailed(item.callId, item.generation, permanent = false, error = MISSING_RESULT, now = now)
                Applied.RETRY
            }
            UploadItemStatus.CREATED, UploadItemStatus.DUPLICATE, UploadItemStatus.UPDATED -> {
                result.serverId?.let { activities.setServerIdentity(item.callId, it, result.version) }
                if (outbox.markSynced(item.callId, item.generation, now)) Applied.UPLOADED else Applied.SUPERSEDED
            }
            UploadItemStatus.CONFLICT -> resolveConflict(item, sent, result, now)
            UploadItemStatus.INVALID if isStaleCrmLink(result.errors, sent) -> {
                healStaleCrmLink(item, sent.phone)
                Applied.RETRY
            }
            UploadItemStatus.INVALID -> {
                val reason = result.errors.joinToString("; ").ifBlank { "Rejected by the CRM" }
                if (outbox.markFailed(item.callId, item.generation, permanent = true, error = reason, now = now)) {
                    Applied.FAILED
                } else {
                    Applied.SUPERSEDED
                }
            }
            UploadItemStatus.ERROR -> {
                outbox.markFailed(item.callId, item.generation, permanent = false, error = TRANSIENT_ITEM, now = now)
                Applied.RETRY
            }
        }
    }

    /**
     * The CRM rejected only because the lead/contact this call is linked to no longer exists (deleted or merged on
     * the CRM side). Retrying unchanged would fail forever, so: forget the cached answer, drop the link and re-run
     * the rule engine for the number (a call that was BUSINESS only because of that CRM record drops out of the
     * outbox here and is never sent), then re-queue this call if it is still BUSINESS. A CRM lookup is requested right
     * away, so a record that now exists under a new id (merged, or moved from contact to lead) re-links the number
     * and re-sends its calls without waiting for the next call or the hourly sweep.
     */
    private suspend fun healStaleCrmLink(item: OutboxItem, phone: String) {
        crm.forget(phone)
        reclassifyNumber(phone)
        syncGate.reconcile(item.callId, contentChanged = true)
        crmLookups.requestLookup()
    }

    /** The mock CRM's wording (mock-server/README.md): "leadId does not exist" / "contactId does not exist". */
    private fun isStaleCrmLink(errors: List<String>, sent: ActivityPayload): Boolean =
        (sent.leadId != null || sent.contactId != null) &&
            errors.isNotEmpty() &&
            errors.all { it == "leadId does not exist" || it == "contactId does not exist" }

    /**
     * The CRM holds a copy at least as new as what was sent. Last write wins: if the CRM's copy is newer than the
     * local one (always true unless the user edited after sending), it replaces the local log; otherwise the local
     * edit is kept and goes out on the next run (as an UPDATED, its timestamp being newer).
     */
    private suspend fun resolveConflict(item: OutboxItem, sent: ActivityPayload, result: UploadItemResult, now: Long): Applied {
        val server = result.server
        val local = activities.getActivity(item.callId)
        if (server == null || local == null) {
            outbox.markFailed(item.callId, item.generation, permanent = false, error = MISSING_RESULT, now = now)
            return Applied.RETRY
        }
        if (server.updatedAt >= local.updatedAt) {
            activities.applyServerCopy(item.callId, server.log, server.updatedAt, result.serverId, result.version)
            outbox.forceSynced(item.callId, now)
            return Applied.UPLOADED
        }
        result.serverId?.let { activities.setServerIdentity(item.callId, it, result.version) }
        if (local.updatedAt > sent.updatedAt) {
            // Edited after sending: that newer edit is already PENDING under a new generation.
            return Applied.SUPERSEDED
        }
        // Can't happen with a correct server (CONFLICT means its copy is at least as new as ours); retry, don't crash.
        outbox.markFailed(item.callId, item.generation, permanent = false, error = MISSING_RESULT, now = now)
        return Applied.RETRY
    }

    companion object {
        /** Bounds one run so a huge backlog yields to WorkManager between runs. */
        const val MAX_ITEMS_PER_RUN = 1000

        const val NO_NUMBER = "This call has no phone number, so the CRM can't store it"
        const val TRANSIENT_ITEM = "The CRM couldn't store it this time; it will be retried"
        const val MISSING_RESULT = "No result from the CRM; it will be retried"

        /**
         * The CRM knows four call types. Blocked calls were refused (REJECTED), voicemail is an unanswered call
         * (MISSED), and "answered elsewhere" (OTHER) was still an incoming conversation.
         */
        fun crmCallType(type: CallType): String = when (type) {
            CallType.INCOMING, CallType.OTHER -> "INCOMING"
            CallType.OUTGOING -> "OUTGOING"
            CallType.MISSED, CallType.VOICEMAIL -> "MISSED"
            CallType.REJECTED, CallType.BLOCKED -> "REJECTED"
        }
    }
}
