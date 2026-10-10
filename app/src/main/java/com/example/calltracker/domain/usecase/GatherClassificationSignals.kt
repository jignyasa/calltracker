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
        normalizedNumber: String?,
        simSlot: Int?,
        workSimSlot: Int?,
    ) = ClassificationSignals(
        userRule = normalizedNumber?.let { rules.getRule(it)?.classification },
        crmMatch = normalizedNumber?.let { crm.cached(it)?.match },
        isOnWorkSim = workSimSlot != null && simSlot == workSimSlot,
    )
}
