package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/** Saves the post-call form for a BUSINESS call and queues the update for sync. */
class SaveActivityLogUseCase @Inject constructor(
    private val calls: CallRepository,
    private val activities: ActivityRepository,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {

    sealed interface Result {
        data object Saved : Result
        data object CallNotFound : Result

        /** Logs can only be attached to BUSINESS calls; the user must reclassify first. */
        data object NotBusiness : Result
        data class Invalid(val fields: Set<Field>) : Result
    }

    enum class Field { NOTES, NEXT_ACTION, REMARKS }

    suspend operator fun invoke(callId: Long, log: ActivityLog): Result {
        val cleaned = log.copy(
            notes = log.notes.cleaned(),
            nextAction = log.nextAction.cleaned(),
            remarks = log.remarks.cleaned(),
        )
        val tooLong = buildSet {
            if (cleaned.notes.isTooLong()) add(Field.NOTES)
            if (cleaned.nextAction.isTooLong()) add(Field.NEXT_ACTION)
            if (cleaned.remarks.isTooLong()) add(Field.REMARKS)
        }
        if (tooLong.isNotEmpty()) return Result.Invalid(tooLong)

        return transaction.run {
            val call = calls.getCall(callId) ?: return@run Result.CallNotFound
            if (call.classification != Classification.BUSINESS) return@run Result.NotBusiness

            val now = clock.millis()
            val current = activities.ensureActivity(callId, now)
            // The edit is newer than the version the user was looking at, even if that version carries a timestamp
            // from a clock that runs ahead (e.g. a CRM copy that won a conflict). Otherwise last-write-wins would
            // silently drop every edit until this phone's clock caught up.
            activities.updateLog(callId, cleaned, maxOf(now, current.updatedAt + 1))
            syncGate.reconcile(callId, contentChanged = true)
            Result.Saved
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String?.isTooLong(): Boolean = (this?.length ?: 0) > ActivityLog.MAX_TEXT_LENGTH
}
