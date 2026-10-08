package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.CallDao
import com.example.calltracker.data.local.toDomain
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
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

    override fun observeCall(id: Long): Flow<CallRecord?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getCall(id: Long): CallRecord? = dao.getById(id)?.toDomain()

    override suspend fun setClassification(callId: Long, classification: Classification, source: ClassificationSource) {
        dao.updateClassification(callId, classification, source, clock.millis())
    }

    override suspend fun applyRuleToNumber(normalizedNumber: String, classification: Classification): List<Long> {
        val ids = dao.idsOverridableByRule(normalizedNumber)
        if (ids.isNotEmpty()) dao.applyRule(ids, classification, clock.millis())
        return ids
    }
}
