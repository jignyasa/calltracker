package com.example.calltracker.domain.model

/** Must match the mock CRM's accepted values (see mock-server/README.md). */
enum class CallOutcome {
    INTERESTED,
    NOT_INTERESTED,
    CALL_BACK,
    NO_ANSWER,
    WRONG_NUMBER,
    CONVERTED,
    OTHER,
}
