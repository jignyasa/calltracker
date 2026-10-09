package com.example.calltracker.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.paging.PagingSource
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/** A call joined with its outbox row's status (null when not queued). */
data class CallWithSync(
    @Embedded val call: CallEntity,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus?,
)

data class UploadCountsRow(
    @ColumnInfo(name = "waiting") val waiting: Int,
    @ColumnInfo(name = "failed") val failed: Int,
)

@Dao
interface CallDao {

    @Query("SELECT * FROM calls ORDER BY started_at DESC")
    fun observeAll(): Flow<List<CallEntity>>

    /** The call list, filtered in SQL. [anySync] = true ignores [statuses] (Room needs a list either way). */
    @Query(
        """
        SELECT c.*, o.status AS sync_status FROM calls c LEFT JOIN outbox o ON o.call_id = c.id
        WHERE (:classification IS NULL OR c.classification = :classification)
          AND (:anySync OR o.status IN (:statuses))
        ORDER BY c.started_at DESC, c.id DESC
        """,
    )
    fun pagedList(classification: Classification?, anySync: Boolean, statuses: List<SyncStatus>): PagingSource<Int, CallWithSync>

    @Query(
        """
        SELECT COALESCE(SUM(status IN ('PENDING', 'IN_FLIGHT', 'FAILED_RETRYABLE')), 0) AS waiting,
               COALESCE(SUM(status = 'FAILED_PERMANENT'), 0) AS failed
        FROM outbox
        """,
    )
    fun observeUploadCounts(): Flow<UploadCountsRow>

    @Query("SELECT * FROM calls WHERE started_at >= :since ORDER BY started_at DESC")
    suspend fun getSince(since: Long): List<CallEntity>

    @Query("SELECT * FROM calls WHERE id = :id")
    fun observeById(id: Long): Flow<CallEntity?>

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun getById(id: Long): CallEntity?

    @Query("SELECT * FROM calls WHERE normalized_number = :number ORDER BY started_at DESC")
    suspend fun getByNumber(number: String): List<CallEntity>

    @Query(
        "UPDATE calls SET crm_type = :type, crm_id = :id, crm_name = :name, crm_company = :company, updated_at = :now " +
            "WHERE normalized_number = :number",
    )
    suspend fun setCrmMatch(number: String, type: CrmRecordType?, id: String?, name: String?, company: String?, now: Long)

    /** See [com.example.calltracker.domain.repository.CallRepository.numbersNeedingCrmLookup]. */
    @Query(
        """
        SELECT c.normalized_number FROM calls c
        LEFT JOIN crm_cache k ON k.normalized_number = c.normalized_number
        WHERE c.normalized_number IS NOT NULL
          AND c.started_at >= :since
          AND (k.normalized_number IS NULL OR k.fetched_at < :staleBefore
               OR k.fetched_at < c.started_at + c.duration_sec * 1000)
          AND c.normalized_number NOT IN
              (SELECT normalized_number FROM number_rules WHERE classification = 'PERSONAL')
          AND c.normalized_number NOT IN
              (SELECT normalized_number FROM calls
               WHERE classification_source = 'USER_MANUAL' AND classification = 'PERSONAL'
                 AND normalized_number IS NOT NULL)
        GROUP BY c.normalized_number
        ORDER BY MAX(c.started_at) DESC
        LIMIT :limit
        """,
    )
    suspend fun numbersNeedingCrmLookup(since: Long, staleBefore: Long, limit: Int): List<String>

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
