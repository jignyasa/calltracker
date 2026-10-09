package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Looks up recent numbers the CRM hasn't answered for yet (or whose answer is stale) and reclassifies their calls.
 * Runs after every sweep; the sweep itself only uses the cache so it never waits for the network.
 */
class ResolveCrmMatchesUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val reclassify: ReclassifyNumberUseCase,
    private val clock: Clock,
) {

    data class Result(
        val lookedUp: Int,
        /** The CRM couldn't be reached; the remaining numbers are still unresolved. */
        val unavailable: Boolean,
        val reclassifiedCallIds: Set<Long>,
    )

    suspend operator fun invoke(): Result {
        val now = clock.millis()
        val numbers = calls.numbersNeedingCrmLookup(
            since = now - LOOKUP_WINDOW_MS,
            staleBefore = now - CACHE_TTL_MS,
            limit = MAX_LOOKUPS_PER_RUN,
        )
        var lookedUp = 0
        val reclassified = mutableSetOf<Long>()
        for (number in numbers) {
            when (crm.lookup(number, now)) {
                // The server or network is down; the rest would fail too.
                CrmLookupResult.Unavailable -> return Result(lookedUp, unavailable = true, reclassified)
                is CrmLookupResult.Found, CrmLookupResult.NotFound -> {
                    lookedUp++
                    reclassified += reclassify(number)
                }
            }
        }
        return Result(lookedUp, unavailable = false, reclassified)
    }

    companion object {
        /** Older calls keep whatever answer they got; history isn't re-queried. */
        val LOOKUP_WINDOW_MS = SweepCallLogUseCase.BACKFILL_MS
        val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)
        const val MAX_LOOKUPS_PER_RUN = 50
    }
}
