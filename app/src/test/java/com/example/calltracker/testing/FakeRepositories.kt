package com.example.calltracker.testing

import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CachedCrmLookup
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.NewLead
import com.example.calltracker.domain.model.NumberRule
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.ActivityRepository
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.CrmRepository
import com.example.calltracker.domain.repository.SettingsRepository
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
    CrmRepository,
    SettingsRepository,
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
        deviceContactName: String? = null,
        simSlot: Int? = null,
        startedAt: Long = 1_790_000_000_000 + id,
    ): CallRecord {
        val call = CallRecord(
            id = id,
            callLogId = id,
            rawNumber = number,
            normalizedNumber = number,
            deviceContactName = deviceContactName,
            type = CallType.OUTGOING,
            startedAt = startedAt,
            durationSec = 60,
            phoneAccountId = null,
            simSlot = simSlot,
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
        crmMatch: CrmMatch?,
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
            crmMatch = crmMatch,
        )
        return id
    }

    override suspend fun getCallsForNumber(normalizedNumber: String): List<CallRecord> =
        calls.value.values.filter { it.normalizedNumber == normalizedNumber }.sortedByDescending { it.startedAt }

    override suspend fun setCrmMatch(normalizedNumber: String, match: CrmMatch?) {
        calls.value = calls.value.mapValues { (_, call) ->
            if (call.normalizedNumber == normalizedNumber) call.copy(crmMatch = match) else call
        }
    }

    override suspend fun numbersNeedingCrmLookup(since: Long, staleBefore: Long, limit: Int): List<String> {
        val personalByHand = calls.value.values
            .filter { it.classificationSource == ClassificationSource.USER_MANUAL && it.classification == Classification.PERSONAL }
            .mapNotNull { it.normalizedNumber }.toSet()
        val personalRules = rules.value.values.filter { it.classification == Classification.PERSONAL }.map { it.normalizedNumber }
        return calls.value.values
            .filter { it.normalizedNumber != null && it.startedAt >= since }
            .groupBy { it.normalizedNumber!! }
            .filterKeys { number ->
                val cached = crmCache[number]
                (cached == null || cached.fetchedAt < staleBefore) && number !in personalByHand && number !in personalRules
            }
            .entries.sortedByDescending { (_, list) -> list.maxOf { it.startedAt } }
            .map { it.key }
            .take(limit)
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

    // CrmRepository: [crmDirectory] is the "server"; [crmCache] the local cache.
    val crmDirectory = mutableMapOf<String, CrmMatch>()
    val crmCache = mutableMapOf<String, CachedCrmLookup>()
    val crmLookups = mutableListOf<String>()
    var crmAvailable = true
    var createLeadResult: ((NewLead, String) -> CreateLeadResult)? = null
    val createLeadRequests = mutableListOf<Pair<NewLead, String>>()

    override suspend fun cached(normalizedNumber: String): CachedCrmLookup? = crmCache[normalizedNumber]

    override suspend fun lookup(normalizedNumber: String, now: Long): CrmLookupResult {
        crmLookups += normalizedNumber
        if (!crmAvailable) return CrmLookupResult.Unavailable
        val match = crmDirectory[normalizedNumber]
        crmCache[normalizedNumber] = CachedCrmLookup(match, now)
        return match?.let { CrmLookupResult.Found(it) } ?: CrmLookupResult.NotFound
    }

    override suspend fun createLead(lead: NewLead, idempotencyKey: String, now: Long): CreateLeadResult {
        createLeadRequests += lead to idempotencyKey
        if (!crmAvailable) return CreateLeadResult.Unavailable
        val result = createLeadResult?.invoke(lead, idempotencyKey)
            ?: crmDirectory[lead.phone]?.let { CreateLeadResult.AlreadyExists(it) }
            ?: CreateLeadResult.Created(CrmMatch(CrmRecordType.LEAD, "lead_${crmDirectory.size + 1}", lead.name, lead.company))
        when (result) {
            is CreateLeadResult.Created -> crmDirectory[lead.phone] = result.lead
            else -> Unit
        }
        val stored = crmDirectory[lead.phone]
        if (stored != null) crmCache[lead.phone] = CachedCrmLookup(stored, now)
        return result
    }

    // SettingsRepository
    private val workSim = MutableStateFlow<Int?>(null)
    override fun observeWorkSimSlot(): Flow<Int?> = workSim
    override suspend fun workSimSlot(): Int? = workSim.value
    override suspend fun setWorkSimSlot(slot: Int?) {
        workSim.value = slot
    }

    // TransactionRunner
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
