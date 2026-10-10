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

    /** The number's saved classification (a per-number rule; every answer the user gives is saved as one). */
    USER_RULE,

    /** No longer produced: the CRM doesn't decide classification. Old rows are turned into rules by MIGRATION_2_3. */
    CRM_MATCH,
    WORK_SIM,

    /** No longer produced: saved phone contacts are asked once. Old rows are turned into rules by MIGRATION_2_3. */
    DEVICE_CONTACT,
    NONE,
}

/** Inputs to [com.example.calltracker.domain.usecase.ClassifyCallUseCase]. */
data class ClassificationSignals(
    /** The user's per-number rule, if any. */
    val userRule: Classification? = null,
    /** The cached CRM record for the number: links the call, never decides its classification. */
    val crmMatch: CrmMatch? = null,
    /** The call used the SIM the user marked as their work SIM. */
    val isOnWorkSim: Boolean = false,
)

data class ClassificationDecision(
    val classification: Classification,
    val source: ClassificationSource,
)
