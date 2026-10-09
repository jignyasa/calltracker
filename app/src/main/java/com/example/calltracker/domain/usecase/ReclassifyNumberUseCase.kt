package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.SettingsRepository
import com.example.calltracker.domain.repository.TransactionRunner
import javax.inject.Inject

/**
 * Re-runs the rule engine for every call from a number after one of its signals changed (a fresh CRM answer, a rule
 * added or removed). Also refreshes the CRM record shown on those calls. See [CallReclassifier].
 */
class ReclassifyNumberUseCase @Inject constructor(
    private val calls: CallRepository,
    private val crm: CrmRepository,
    private val settings: SettingsRepository,
    private val reclassifier: CallReclassifier,
    private val transaction: TransactionRunner,
) {
    /** @return ids of the calls whose classification changed. */
    suspend operator fun invoke(normalizedNumber: String): Set<Long> {
        val workSimSlot = settings.workSimSlot()
        return transaction.run {
            calls.setCrmMatch(normalizedNumber, crm.cached(normalizedNumber)?.match)
            calls.getCallsForNumber(normalizedNumber)
                .filter { reclassifier.reclassify(it, workSimSlot) }
                .map { it.id }
                .toSet()
        }
    }
}
