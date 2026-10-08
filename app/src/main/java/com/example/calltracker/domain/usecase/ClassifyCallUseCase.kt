package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSignals
import javax.inject.Inject

/**
 * Decides business vs personal without asking the user whenever there is a clear signal.
 * Precedence, highest first:
 * 1. explicit per-number user rule
 * 2. number exists in the CRM → BUSINESS
 * 3. call on the work SIM → BUSINESS
 * 4. saved device contact that is not in the CRM → PERSONAL
 * 5. otherwise UNCLASSIFIED (the user is asked; nothing is synced meanwhile)
 */
class ClassifyCallUseCase @Inject constructor() {

    operator fun invoke(signals: ClassificationSignals): ClassificationDecision = when {
        signals.userRule != null && signals.userRule != Classification.UNCLASSIFIED ->
            ClassificationDecision(signals.userRule, ClassificationSource.USER_RULE)
        signals.crmMatch != null ->
            ClassificationDecision(Classification.BUSINESS, ClassificationSource.CRM_MATCH)
        signals.isOnWorkSim ->
            ClassificationDecision(Classification.BUSINESS, ClassificationSource.WORK_SIM)
        signals.isDeviceContact ->
            ClassificationDecision(Classification.PERSONAL, ClassificationSource.DEVICE_CONTACT)
        else ->
            ClassificationDecision(Classification.UNCLASSIFIED, ClassificationSource.NONE)
    }
}
