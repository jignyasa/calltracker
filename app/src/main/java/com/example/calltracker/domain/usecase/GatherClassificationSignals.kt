package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ClassificationSignals
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.NumberRuleRepository
import javax.inject.Inject

/**
 * Collects the local signals for one call: the number's rule (its saved classification), whether it was on the work
 * SIM, and the cached CRM record (no network; only for linking). The sweep and reclassification share it so both
 * apply the same precedence.
 */
class GatherClassificationSignals @Inject constructor(
    private val rules: NumberRuleRepository,
    private val crm: CrmRepository,
) {
    suspend operator fun invoke(
        rawNumber: String?,
        normalizedNumber: String?,
        simSlot: Int?,
        workSimSlot: Int?,
    ) = ClassificationSignals(
        userRule = normalizedNumber?.let { rules.getRule(it)?.classification },
        crmMatch = normalizedNumber?.let { crm.cached(it)?.match },
        isOnWorkSim = workSimSlot != null && simSlot == workSimSlot,
        isServiceNumber = normalizedNumber == null && isShownNumber(rawNumber),
    )

    /**
     * A number the caller didn't hide. CallLog reports private / unknown / payphone callers as "-1", "-2", "-3" (or
     * empty); anything else with digits that still isn't a full number is a short code such as 198 or 121.
     */
    private fun isShownNumber(raw: String?): Boolean {
        val input = raw?.trim().orEmpty()
        return !input.startsWith("-") && input.any { it.isDigit() }
    }
}
