package com.example.calltracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.calltracker.data.local.entity.NumberRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NumberRuleDao {

    @Query("SELECT * FROM number_rules ORDER BY created_at DESC")
    fun observeAll(): Flow<List<NumberRuleEntity>>

    @Query("SELECT * FROM number_rules WHERE normalized_number = :number")
    suspend fun get(number: String): NumberRuleEntity?

    @Upsert
    suspend fun upsert(rule: NumberRuleEntity)

    @Query("DELETE FROM number_rules WHERE normalized_number = :number")
    suspend fun delete(number: String)
}
