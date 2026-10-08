package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.ClassificationDecision
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun observeCalls(): Flow<List<CallRecord>>

    fun observeCall(id: Long): Flow<CallRecord?>

    suspend fun getCall(id: Long): CallRecord?

    /**
     * Stores a CallLog row unless it was imported before.
     * @return the new call id, or null if the row already exists.
     */
    suspend fun insertIfNew(
        entry: CallLogEntry,
        normalizedNumber: String?,
        decision: ClassificationDecision,
        now: Long,
    ): Long?

    suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource)

    /**
     * Applies a per-number rule to every stored call from [normalizedNumber] that the user hasn't classified by hand.
     * @return ids of the calls that were updated.
     */
    suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long>
}
