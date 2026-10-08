package com.example.calltracker.domain.model

/** "Always treat this number as …". [classification] is BUSINESS or PERSONAL. */
data class NumberRule(
    val normalizedNumber: String,
    val classification: Classification,
    val createdAt: Long,
)
