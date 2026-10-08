package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.NumberRule
import kotlinx.coroutines.flow.Flow

interface NumberRuleRepository {
    fun observeRules(): Flow<List<NumberRule>>

    suspend fun getRule(normalizedNumber: String): NumberRule?

    suspend fun setRule(normalizedNumber: String, classification: Classification, now: Long)

    suspend fun deleteRule(normalizedNumber: String)
}
