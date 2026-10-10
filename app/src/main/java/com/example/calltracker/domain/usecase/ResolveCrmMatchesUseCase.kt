package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.SettingsRepository
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Looks up recent **professional** numbers the CRM hasn't answered for yet (or whose answer is stale) and links their
 * calls to the Lead/Contact found. Personal and unsorted numbers are never sent. When a number isn't in the CRM and
 * auto-create is on, the lead is created from the phonebook name (Leads and Contacts were just checked, and the server
 * refuses a duplicate phone, so no duplicate is made). Runs after every sweep and whenever a call becomes professional.
 */
class ResolveCrmMatchesUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val reclassify: ReclassifyNumberUseCase,
    private val createLead: CreateLeadFromCallUseCase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {

    data class Result(
        val lookedUp: Int,
        /** The CRM couldn't be reached; the remaining numbers are still unresolved. */
        val unavailable: Boolean,
        val reclassifiedCallIds: Set<Long>,
        /** Leads created (or found) automatically for professional numbers that weren't in the CRM. */
        val leadsCreated: Int = 0,
    )

    suspend operator fun invoke(): Result {
        val now = clock.millis()
        val numbers = calls.numbersNeedingCrmLookup(
            since = now - LOOKUP_WINDOW_MS,
            staleBefore = now - CACHE_TTL_MS,
            limit = MAX_LOOKUPS_PER_RUN,
        )
        var lookedUp = 0
        var leadsCreated = 0
        val reclassified = mutableSetOf<Long>()
        for (number in numbers) {
            val answer = crm.lookup(number, now)
            // The server or network is down; the rest would fail too.
            if (answer == CrmLookupResult.Unavailable) return Result(lookedUp, unavailable = true, reclassified, leadsCreated)
            lookedUp++
            reclassified += reclassify(number)
            if (answer == CrmLookupResult.NotFound) {
                when (autoCreateLead(number)) {
                    true -> leadsCreated++
                    false -> Unit
                    // Offline mid-way: forget the "not found" so the next run looks up (and creates) again.
                    null -> {
                        crm.forget(number)
                        return Result(lookedUp, unavailable = true, reclassified, leadsCreated)
                    }
                }
            }
        }
        return Result(lookedUp, unavailable = false, reclassified, leadsCreated)
    }

    /** @return true if a lead was created or found, false if not attempted or refused, null if the CRM was unreachable. */
    private suspend fun autoCreateLead(number: String): Boolean? {
        if (!settings.autoCreateLeads()) return false
        val call = calls.getCallsForNumber(number)
            .filter { it.classification == Classification.BUSINESS && !it.deviceContactName.isNullOrBlank() }
            .maxByOrNull { it.startedAt } ?: return false
        return when (createLead(call.id, call.deviceContactName.orEmpty(), company = null)) {
            is CreateLeadFromCallUseCase.Result.Created, is CreateLeadFromCallUseCase.Result.AlreadyInCrm -> true
            CreateLeadFromCallUseCase.Result.Unavailable -> null
            else -> false
        }
    }

    companion object {
        /** Older calls keep whatever answer they got; history isn't re-queried. */
        val LOOKUP_WINDOW_MS = SweepCallLogUseCase.BACKFILL_MS
        val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)
        const val MAX_LOOKUPS_PER_RUN = 50
    }
}
