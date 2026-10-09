package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.NewLead
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import java.util.UUID
import javax.inject.Inject

/**
 * Creates a CRM lead for the caller of a call. On success (or if the number turns out to be in the CRM already) the
 * number's calls are reclassified as CRM matches, and the call it was created from becomes BUSINESS: creating a lead
 * is the user saying "this is a work call".
 */
class CreateLeadFromCallUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val reclassify: ReclassifyNumberUseCase,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {

    sealed interface Result {
        data class Created(val lead: CrmMatch) : Result
        data class AlreadyInCrm(val match: CrmMatch) : Result
        data object CallNotFound : Result

        /** Private or unparseable number. */
        data object NoNumber : Result
        data object NameRequired : Result
        data class Rejected(val reason: String) : Result

        /** Offline or server error; the same request can be retried safely. */
        data object Unavailable : Result
    }

    suspend operator fun invoke(callId: Long, name: String, company: String?): Result {
        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: return Result.NameRequired
        val cleanCompany = company?.trim()?.takeIf { it.isNotEmpty() }
        val call = calls.getCall(callId) ?: return Result.CallNotFound
        val number = call.normalizedNumber ?: return Result.NoNumber

        val lead = NewLead(cleanName, number, cleanCompany)
        val result = when (val created = crm.createLead(lead, idempotencyKey(lead), clock.millis())) {
            is CreateLeadResult.Created -> Result.Created(created.lead)
            is CreateLeadResult.AlreadyExists -> Result.AlreadyInCrm(created.match)
            is CreateLeadResult.Rejected -> return Result.Rejected(created.reason)
            CreateLeadResult.Unavailable -> return Result.Unavailable
        }

        transaction.run {
            reclassify(number)
            val current = calls.getCall(callId)
            if (current != null && current.classification != Classification.BUSINESS) {
                calls.setClassification(callId, Classification.BUSINESS, ClassificationSource.USER_MANUAL)
                syncGate.reconcile(callId)
            }
        }
        return result
    }

    companion object {
        /**
         * Same lead details → same key, so a retry after a timeout replays the original response instead of
         * creating a duplicate; changed details get a new key (the server rejects a reused key with a new body).
         */
        fun idempotencyKey(lead: NewLead): String =
            UUID.nameUUIDFromBytes("lead|${lead.phone}|${lead.name}|${lead.company.orEmpty()}".toByteArray()).toString()
    }
}
