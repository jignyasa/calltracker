package com.example.calltracker.testing

import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.NumberRule
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.OutboxRepository
import com.example.calltracker.domain.repository.SweepStateRepository
import com.example.calltracker.domain.repository.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** In-memory implementation of every domain repository, sharing one state like the real database does. */
class FakeRepositories :
    CallRepository,
    ActivityRepository,
    OutboxRepository,
    NumberRuleRepository,
    SweepStateRepository,
    TransactionRunner {

    data class OutboxItem(val clientId: String, val status: SyncStatus, val generation: Int)

    private val calls = MutableStateFlow<Map<Long, CallRecord>>(emptyMap())
    private val activities = MutableStateFlow<Map<Long, Activity>>(emptyMap())
    val outbox = mutableMapOf<Long, OutboxItem>()
    private val rules = MutableStateFlow<Map<String, NumberRule>>(emptyMap())

    fun addCall(
        id: Long,
        number: String? = "+919876543210",
        classification: Classification = Classification.UNCLASSIFIED,
        source: ClassificationSource = ClassificationSource.NONE,
    ): CallRecord {
        val call = CallRecord(
            id = id,
            callLogId = id,
            rawNumber = number,
            normalizedNumber = number,
            deviceContactName = null,
            type = CallType.OUTGOING,
            startedAt = 1_790_000_000_000 + id,
            durationSec = 60,
            phoneAccountId = null,
            simSlot = null,
            classification = classification,
            classificationSource = source,
            crmMatch = null,
        )
        calls.value += id to call
        return call
    }

    fun markSynced(callId: Long) {
        outbox[callId] = outbox.getValue(callId).copy(status = SyncStatus.SYNCED)
    }

    // CallRepository
    override fun observeCalls(): Flow<List<CallRecord>> = calls.map { it.values.toList() }
    override fun observeCall(id: Long): Flow<CallRecord?> = calls.map { it[id] }
    override suspend fun getCall(id: Long): CallRecord? = calls.value[id]

    override suspend fun insertIfNew(
        entry: CallLogEntry,
        normalizedNumber: String?,
        decision: ClassificationDecision,
        now: Long,
    ): Long? {
        if (calls.value.values.any { it.callLogId == entry.callLogId && it.startedAt == entry.startedAt }) return null
        val id = (calls.value.keys.maxOrNull() ?: 0) + 1
        calls.value += id to CallRecord(
            id = id,
            callLogId = entry.callLogId,
            rawNumber = entry.rawNumber,
            normalizedNumber = normalizedNumber,
            deviceContactName = entry.cachedName,
            type = entry.type,
            startedAt = entry.startedAt,
            durationSec = entry.durationSec,
            phoneAccountId = entry.phoneAccountId,
            simSlot = entry.simSlot,
            classification = decision.classification,
            classificationSource = decision.source,
            crmMatch = null,
        )
        return id
    }

    val allCalls: List<CallRecord> get() = calls.value.values.sortedBy { it.id }

    override suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource) {
        val call = calls.value[callId] ?: return
        calls.value += callId to call.copy(classification = classification, classificationSource = source)
    }

    override suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long> {
        val ids = calls.value.values
            .filter { it.normalizedNumber == normalizedNumber && it.classificationSource != ClassificationSource.USER_MANUAL }
            .map { it.id }
        ids.forEach { setClassification(it, classification, ClassificationSource.USER_RULE) }
        return ids
    }

    // ActivityRepository
    override fun observeActivity(callId: Long): Flow<Activity?> = activities.map { it[callId]?.withSync() }
    override suspend fun getActivity(callId: Long): Activity? = activities.value[callId]?.withSync()

    override suspend fun ensureActivity(callId: Long, now: Long): Activity {
        activities.value[callId]?.let { return it.withSync() }
        val activity = Activity(callId, UUID.randomUUID().toString(), ActivityLog(), now, null, null, null)
        activities.value += callId to activity
        return activity.withSync()
    }

    override suspend fun updateLog(callId: Long, log: ActivityLog, now: Long) {
        val activity = checkNotNull(activities.value[callId])
        activities.value += callId to activity.copy(log = log, updatedAt = now)
    }

    private fun Activity.withSync() = copy(syncStatus = outbox[callId]?.status)

    // OutboxRepository
    override suspend fun enqueue(callId: Long, clientId: String, now: Long) {
        val existing = outbox[callId]
        outbox[callId] = OutboxItem(clientId, SyncStatus.PENDING, (existing?.generation ?: 0) + 1)
    }

    override suspend fun removeUnsynced(callId: Long) {
        if (outbox[callId]?.status != SyncStatus.SYNCED) outbox.remove(callId)
    }

    // NumberRuleRepository
    override fun observeRules(): Flow<List<NumberRule>> = rules.map { it.values.toList() }
    override suspend fun getRule(normalizedNumber: String): NumberRule? = rules.value[normalizedNumber]

    override suspend fun setRule(normalizedNumber: String, classification: Classification, now: Long) {
        rules.value += normalizedNumber to NumberRule(normalizedNumber, classification, now)
    }

    override suspend fun deleteRule(normalizedNumber: String) {
        rules.value -= normalizedNumber
    }

    // SweepStateRepository
    var watermark: SweepWatermark? = null
    override suspend fun get(): SweepWatermark? = watermark
    override suspend fun save(watermark: SweepWatermark, sweptAt: Long) {
        this.watermark = watermark
    }

    // TransactionRunner
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
