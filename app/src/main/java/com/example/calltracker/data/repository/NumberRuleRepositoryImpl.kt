package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.NumberRuleDao
import com.example.calltracker.data.local.entity.NumberRuleEntity
import com.example.calltracker.data.local.toDomain
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.NumberRule
import com.example.calltracker.domain.repository.NumberRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NumberRuleRepositoryImpl @Inject constructor(
    private val dao: NumberRuleDao,
) : NumberRuleRepository {

    override fun observeRules(): Flow<List<NumberRule>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getRule(normalizedNumber: String): NumberRule? = dao.get(normalizedNumber)?.toDomain()

    override suspend fun setRule(normalizedNumber: String, classification: Classification, now: Long) {
        require(classification != Classification.UNCLASSIFIED) { "Rules are BUSINESS or PERSONAL" }
        dao.upsert(NumberRuleEntity(normalizedNumber, classification, now))
    }

    override suspend fun deleteRule(normalizedNumber: String) = dao.delete(normalizedNumber)
}
