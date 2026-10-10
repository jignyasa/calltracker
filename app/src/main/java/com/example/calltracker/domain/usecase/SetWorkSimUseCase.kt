package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmLookupRequester
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * Changes the work SIM and re-applies the rule engine to the last [SweepCallLogUseCase.BACKFILL_MS] of calls, so
 * recent calls agree with the new setting. Older calls keep their state. Calls that became professional are then
 * looked up in the CRM.
 */
class SetWorkSimUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val calls: CallRepository,
    private val reclassifier: CallReclassifier,
    private val crmLookups: CrmLookupRequester,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    /** @return how many calls changed classification. */
    suspend operator fun invoke(slot: Int?): Int {
        settings.setWorkSimSlot(slot)
        val changed = transaction.run {
            calls.getCallsSince(clock.millis() - SweepCallLogUseCase.BACKFILL_MS)
                .count { reclassifier.reclassify(it, slot) }
        }
        if (slot != null && changed > 0) crmLookups.requestLookup()
        return changed
    }
}
