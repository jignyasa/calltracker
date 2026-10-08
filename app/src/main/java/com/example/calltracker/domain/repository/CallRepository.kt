package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun observeCalls(): Flow<List<CallRecord>>

    fun observeCall(id: Long): Flow<CallRecord?>

    suspend fun getCall(id: Long): CallRecord?

    suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource)

    /**
     * Applies a per-number rule to every stored call from [normalizedNumber] that the user hasn't classified by hand.
     * @return ids of the calls that were updated.
     */
    suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long>
}
