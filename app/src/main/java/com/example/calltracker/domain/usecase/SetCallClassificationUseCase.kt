package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallPromptDismisser
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmLookupRequester
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * The user marks a call professional or personal. With [applyToNumber] (what every screen passes by default) the answer
 * is saved for the number, so its other calls follow and it is never asked about again. A professional answer asks
 * for a CRM lookup straight away, to link the call (or create the lead).
 */
class SetCallClassificationUseCase @Inject constructor(
    private val calls: CallRepository,
    private val activities: ActivityRepository,
    private val rules: NumberRuleRepository,
    private val syncGate: SyncGate,
    private val crmLookups: CrmLookupRequester,
    private val prompts: CallPromptDismisser,
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

        val result = transaction.run {
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
        if (result is Result.Success) {
            // Answered here, so the number's open "Work call?" prompts are no longer needed.
            prompts.dismiss(result.updatedCallIds)
            if (classification == Classification.BUSINESS) crmLookups.requestLookup()
        }
        return result
    }
}
