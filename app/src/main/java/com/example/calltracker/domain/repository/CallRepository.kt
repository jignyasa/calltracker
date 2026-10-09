package com.example.calltracker.domain.repository

import androidx.paging.PagingData
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.CallListQuery
import com.example.calltracker.domain.model.UploadCounts
import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.CrmMatch
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    fun observeCalls(): Flow<List<CallRecord>>

    /**
     * Newest first, each with its sync state, filtered and paged by the database so the list stays light with
     * thousands of calls. (Paging's core is pure Kotlin, so the domain stays JVM-only.)
     */
    fun pagedCallList(query: CallListQuery): Flow<PagingData<CallListEntry>>

    fun observeUploadCounts(): Flow<UploadCounts>

    suspend fun getCallsSince(since: Long): List<CallRecord>

    fun observeCall(id: Long): Flow<CallRecord?>

    suspend fun getCall(id: Long): CallRecord?

    suspend fun getCallsForNumber(normalizedNumber: String): List<CallRecord>

    /**
     * Stores a CallLog row unless it was imported before.
     * @return the new call id, or null if the row already exists.
     */
    suspend fun insertIfNew(
        entry: CallLogEntry,
        normalizedNumber: String?,
        decision: ClassificationDecision,
        crmMatch: CrmMatch?,
        now: Long,
    ): Long?

    suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource)

    /** Attaches (or clears) the CRM record on every stored call from [normalizedNumber]. Doesn't classify. */
    suspend fun setCrmMatch(normalizedNumber: String, match: CrmMatch?)

    /**
     * Numbers with calls since [since] whose CRM answer is missing or older than [staleBefore], most recent first.
     * Never includes a number the user marked personal (by rule, or by hand on any of its calls): those are not
     * sent to the CRM at all.
     */
    suspend fun numbersNeedingCrmLookup(since: Long, staleBefore: Long, limit: Int): List<String>

    /**
     * Applies a per-number rule to every stored call from [normalizedNumber] that the user hasn't classified by hand.
     * @return ids of the calls that were updated.
     */
    suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long>
}
