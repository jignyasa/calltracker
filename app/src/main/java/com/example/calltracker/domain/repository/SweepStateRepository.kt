package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.SweepWatermark

interface SweepStateRepository {
    /** Null before the first sweep. */
    suspend fun get(): SweepWatermark?

    suspend fun save(watermark: SweepWatermark, sweptAt: Long)
}
