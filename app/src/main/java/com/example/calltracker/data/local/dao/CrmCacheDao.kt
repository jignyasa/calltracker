package com.example.calltracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.calltracker.data.local.entity.CrmCacheEntity

@Dao
interface CrmCacheDao {

    @Query("SELECT * FROM crm_cache WHERE normalized_number = :number")
    suspend fun get(number: String): CrmCacheEntity?

    @Upsert
    suspend fun upsert(entry: CrmCacheEntity)
}
