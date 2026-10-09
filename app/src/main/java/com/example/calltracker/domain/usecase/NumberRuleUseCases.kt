package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.phone.PhoneNumberNormalizer
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import javax.inject.Inject

/**
 * Adds or changes an "always business / always personal" rule and applies it to the number's calls
 * (except calls classified by hand), queueing or dequeueing them in the same transaction.
 */
class SetNumberRuleUseCase @Inject constructor(
    private val rules: NumberRuleRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val reclassifyNumber: ReclassifyNumberUseCase,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Saved(val normalizedNumber: String, val reclassifiedCalls: Int) : Result
        data object InvalidNumber : Result
    }

    /** [number] may be typed by the user in any common Indian format; it's normalised first. */
    suspend operator fun invoke(number: String, classification: Classification): Result {
        require(classification != Classification.UNCLASSIFIED) { "Rules are BUSINESS or PERSONAL" }
        val normalized = normalizer.normalize(number) ?: return Result.InvalidNumber
        return transaction.run {
            rules.setRule(normalized, classification, clock.millis())
            Result.Saved(normalized, reclassifyNumber(normalized).size)
        }
    }
}

/** Removes a rule; the number's calls fall back to the automatic signals (CRM, work SIM, contact). */
class DeleteNumberRuleUseCase @Inject constructor(
    private val rules: NumberRuleRepository,
    private val reclassifyNumber: ReclassifyNumberUseCase,
    private val transaction: TransactionRunner,
) {
    /** @return how many calls changed classification. */
    suspend operator fun invoke(normalizedNumber: String): Int = transaction.run {
        rules.deleteRule(normalizedNumber)
        reclassifyNumber(normalizedNumber).size
    }
}
