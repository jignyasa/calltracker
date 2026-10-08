package com.example.calltracker.domain.model

enum class Classification {
    BUSINESS,
    PERSONAL,
    UNCLASSIFIED,
}

/** Why a call has its current [Classification]; shown in the UI and used to decide what a new rule may override. */
enum class ClassificationSource {
    /** The user picked it for this specific call. Never overridden automatically. */
    USER_MANUAL,

    /** A per-number "always business/personal" rule. */
    USER_RULE,
    CRM_MATCH,
    WORK_SIM,
    DEVICE_CONTACT,
    NONE,
}

/** Inputs to [com.example.calltracker.domain.usecase.ClassifyCallUseCase]. */
data class ClassificationSignals(
    /** The user's per-number rule, if any. */
    val userRule: Classification? = null,
    val crmMatch: CrmMatch? = null,
    /** The call used the SIM the user marked as their work SIM. */
    val isOnWorkSim: Boolean = false,
    /** The number is saved in the phone's own contacts. */
    val isDeviceContact: Boolean = false,
)

data class ClassificationDecision(
    val classification: Classification,
    val source: ClassificationSource,
)
