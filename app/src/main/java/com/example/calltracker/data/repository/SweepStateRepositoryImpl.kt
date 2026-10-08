package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.SweepStateDao
import com.example.calltracker.data.local.entity.SweepStateEntity
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.repository.SweepStateRepository
import javax.inject.Inject

class SweepStateRepositoryImpl @Inject constructor(
    private val dao: SweepStateDao,
) : SweepStateRepository {

    override suspend fun get(): SweepWatermark? = dao.get()?.let { SweepWatermark(it.lastCallLogId, it.lastStartedAt) }

    override suspend fun save(watermark: SweepWatermark, sweptAt: Long) {
        dao.upsert(
            SweepStateEntity(
                lastCallLogId = watermark.lastCallLogId,
                lastStartedAt = watermark.lastStartedAt,
                lastSweepAt = sweptAt,
            ),
        )
    }
}
