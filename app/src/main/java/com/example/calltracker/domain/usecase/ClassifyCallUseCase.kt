package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSignals
import com.example.calltracker.domain.model.ClassificationSource
import javax.inject.Inject

/**
 * Decides professional vs personal from what is already known, and otherwise leaves the call for the user to answer.
 * Precedence, highest first:
 * 1. the number's saved classification (a rule: every answer the user gives is saved for the number)
 * 2. call on the work SIM → BUSINESS
 * 3. otherwise UNCLASSIFIED: the user is asked once, and the answer is saved. Nothing is synced meanwhile.
 *
 * The CRM is not a signal: it is searched only for professional calls, after this decision (it links the call to a
 * Lead/Contact). A saved phone contact isn't one either: phonebooks hold customers too, so the user is asked once.
 */
class ClassifyCallUseCase @Inject constructor() {

    operator fun invoke(signals: ClassificationSignals): ClassificationDecision = when {
        signals.userRule != null && signals.userRule != Classification.UNCLASSIFIED ->
            ClassificationDecision(signals.userRule, ClassificationSource.USER_RULE)
        signals.isOnWorkSim ->
            ClassificationDecision(Classification.BUSINESS, ClassificationSource.WORK_SIM)
        else ->
            ClassificationDecision(Classification.UNCLASSIFIED, ClassificationSource.NONE)
    }
}
