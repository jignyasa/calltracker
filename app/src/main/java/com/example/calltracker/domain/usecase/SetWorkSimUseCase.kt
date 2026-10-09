package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * Changes the work SIM and re-applies the rule engine to the last [SweepCallLogUseCase.BACKFILL_MS] of calls, so
 * recent calls agree with the new setting (CRM lookups re-run it per number anyway). Older calls keep their state.
 */
class SetWorkSimUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val calls: CallRepository,
    private val reclassifier: CallReclassifier,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    /** @return how many calls changed classification. */
    suspend operator fun invoke(slot: Int?): Int {
        settings.setWorkSimSlot(slot)
        return transaction.run {
            calls.getCallsSince(clock.millis() - SweepCallLogUseCase.BACKFILL_MS)
                .count { reclassifier.reclassify(it, slot) }
        }
    }
}
