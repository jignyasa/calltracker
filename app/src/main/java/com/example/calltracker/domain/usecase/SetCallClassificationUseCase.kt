package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/** The user marks a call business or personal, optionally remembering the choice for the number. */
class SetCallClassificationUseCase @Inject constructor(
    private val calls: CallRepository,
    private val activities: ActivityRepository,
    private val rules: NumberRuleRepository,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {

    sealed interface Result {
        /**
         * @param alreadyUploaded the call was marked PERSONAL but its activity had already reached the CRM.
         * The app can't retract it, so the UI should tell the user.
         */
        data class Success(val updatedCallIds: Set<Long>, val alreadyUploaded: Boolean) : Result
        data object CallNotFound : Result
    }

    suspend operator fun invoke(
        callId: Long,
        classification: Classification,
        applyToNumber: Boolean,
    ): Result {
        require(classification != Classification.UNCLASSIFIED) { "User can only choose BUSINESS or PERSONAL" }

        return transaction.run {
            val call = calls.getCall(callId) ?: return@run Result.CallNotFound
            val wasUploaded = activities.getActivity(callId)?.syncStatus == SyncStatus.SYNCED

            calls.setClassification(callId, classification, ClassificationSource.USER_MANUAL)
            val updated = mutableSetOf(callId)

            val number = call.normalizedNumber
            if (applyToNumber && number != null) {
                rules.setRule(number, classification, clock.millis())
                updated += calls.applyRuleToNumber(number, classification)
            }
            updated.forEach { syncGate.reconcile(it) }

            Result.Success(
                updatedCallIds = updated,
                alreadyUploaded = wasUploaded && classification == Classification.PERSONAL,
            )
        }
    }
}
