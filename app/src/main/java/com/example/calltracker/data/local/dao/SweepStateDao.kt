package com.example.calltracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.calltracker.data.local.entity.SweepStateEntity

@Dao
interface SweepStateDao {

    @Query("SELECT * FROM sweep_state WHERE id = ${SweepStateEntity.SINGLETON_ID}")
    suspend fun get(): SweepStateEntity?

    @Upsert
    suspend fun upsert(state: SweepStateEntity)
}
