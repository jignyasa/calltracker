package com.example.calltracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {

    @Query("SELECT * FROM calls ORDER BY started_at DESC")
    fun observeAll(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE id = :id")
    fun observeById(id: Long): Flow<CallEntity?>

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun getById(id: Long): CallEntity?

    /** Returns the new row id, or -1 if this CallLog row is already stored (idempotent re-sweeps). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(call: CallEntity): Long

    @Query(
        "UPDATE calls SET classification = :classification, classification_source = :source, updated_at = :now " +
            "WHERE id = :id",
    )
    suspend fun updateClassification(id: Long, classification: Classification, source: ClassificationSource, now: Long)

    @Query(
        "SELECT id FROM calls WHERE normalized_number = :number AND classification_source != 'USER_MANUAL'",
    )
    suspend fun idsOverridableByRule(number: String): List<Long>

    @Query(
        "UPDATE calls SET classification = :classification, classification_source = 'USER_RULE', updated_at = :now " +
            "WHERE id IN (:ids)",
    )
    suspend fun applyRule(ids: List<Long>, classification: Classification, now: Long)
}
