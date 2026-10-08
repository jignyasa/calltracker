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
