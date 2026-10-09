package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.CallDao
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.data.local.toDomain
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.repository.CallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject

class CallRepositoryImpl @Inject constructor(
    private val dao: CallDao,
    private val clock: Clock,
) : CallRepository {

    override fun observeCalls(): Flow<List<CallRecord>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeCallList(): Flow<List<CallListEntry>> =
        dao.observeListWithSync().map { list -> list.map { CallListEntry(it.call.toDomain(), it.syncStatus) } }

    override suspend fun getCallsSince(since: Long): List<CallRecord> = dao.getSince(since).map { it.toDomain() }

    override fun observeCall(id: Long): Flow<CallRecord?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getCall(id: Long): CallRecord? = dao.getById(id)?.toDomain()

    override suspend fun getCallsForNumber(normalizedNumber: String): List<CallRecord> =
        dao.getByNumber(normalizedNumber).map { it.toDomain() }

    override suspend fun insertIfNew(
        entry: CallLogEntry,
        normalizedNumber: String?,
        decision: ClassificationDecision,
        crmMatch: CrmMatch?,
        now: Long,
    ): Long? {
        val id = dao.insertIgnore(
            CallEntity(
                callLogId = entry.callLogId,
                startedAt = entry.startedAt,
                rawNumber = entry.rawNumber,
                normalizedNumber = normalizedNumber,
                deviceContactName = entry.cachedName,
                type = entry.type,
                durationSec = entry.durationSec,
                phoneAccountId = entry.phoneAccountId,
                simSlot = entry.simSlot,
                classification = decision.classification,
                classificationSource = decision.source,
                crmType = crmMatch?.type,
                crmId = crmMatch?.id,
                crmName = crmMatch?.name,
                crmCompany = crmMatch?.company,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id.takeIf { it > 0 }
    }

    override suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource) {
        dao.updateClassification(callId, classification, source, clock.millis())
    }

    override suspend fun setCrmMatch(normalizedNumber: String, match: CrmMatch?) {
        dao.setCrmMatch(normalizedNumber, match?.type, match?.id, match?.name, match?.company, clock.millis())
    }

    override suspend fun numbersNeedingCrmLookup(since: Long, staleBefore: Long, limit: Int): List<String> =
        dao.numbersNeedingCrmLookup(since, staleBefore, limit)

    override suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long> {
        val ids = dao.idsOverridableByRule(normalizedNumber)
        if (ids.isNotEmpty()) dao.applyRule(ids, classification, clock.millis())
        return ids
    }
}
