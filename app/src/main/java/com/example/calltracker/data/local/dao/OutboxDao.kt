package com.example.calltracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.calltracker.data.local.entity.OutboxEntity
import com.example.calltracker.domain.model.SyncStatus

@Dao
interface OutboxDao {

    @Query("SELECT * FROM outbox WHERE call_id = :callId")
    suspend fun get(callId: Long): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: OutboxEntity)

    /** Re-arms an existing item: back to PENDING with a new generation and a fresh retry budget. */
    @Query(
        "UPDATE outbox SET status = 'PENDING', generation = generation + 1, attempts = 0, last_error = NULL, " +
            "enqueued_at = :now, updated_at = :now WHERE call_id = :callId",
    )
    suspend fun rearm(callId: Long, now: Long): Int

    @Transaction
    suspend fun enqueue(callId: Long, clientId: String, now: Long) {
        if (rearm(callId, now) == 0) {
            insert(
                OutboxEntity(
                    callId = callId,
                    clientId = clientId,
                    status = SyncStatus.PENDING,
                    generation = 1,
                    attempts = 0,
                    lastError = null,
                    enqueuedAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Query("DELETE FROM outbox WHERE call_id = :callId AND status != 'SYNCED'")
    suspend fun deleteUnsynced(callId: Long): Int

    @Query("UPDATE outbox SET status = 'PENDING', updated_at = :now WHERE status = 'IN_FLIGHT'")
    suspend fun resetInFlight(now: Long): Int

    @Query(
        "SELECT * FROM outbox WHERE status IN ('PENDING', 'FAILED_RETRYABLE') AND call_id NOT IN (:exclude) " +
            "ORDER BY enqueued_at, call_id LIMIT :limit",
    )
    suspend fun uploadable(limit: Int, exclude: List<Long>): List<OutboxEntity>

    @Query("UPDATE outbox SET status = 'IN_FLIGHT', updated_at = :now WHERE call_id IN (:callIds)")
    suspend fun setInFlight(callIds: List<Long>, now: Long)

    @Transaction
    suspend fun claim(limit: Int, exclude: List<Long>, now: Long): List<OutboxEntity> {
        val items = uploadable(limit, exclude)
        if (items.isNotEmpty()) setInFlight(items.map { it.callId }, now)
        return items.map { it.copy(status = SyncStatus.IN_FLIGHT, updatedAt = now) }
    }

    @Query(
        "UPDATE outbox SET status = 'SYNCED', last_error = NULL, updated_at = :now " +
            "WHERE call_id = :callId AND generation = :generation",
    )
    suspend fun markSynced(callId: Long, generation: Int, now: Long): Int

    @Query("UPDATE outbox SET status = 'SYNCED', last_error = NULL, updated_at = :now WHERE call_id = :callId")
    suspend fun forceSynced(callId: Long, now: Long)

    @Query(
        "UPDATE outbox SET status = :status, attempts = attempts + 1, last_error = :error, updated_at = :now " +
            "WHERE call_id = :callId AND generation = :generation",
    )
    suspend fun markFailed(callId: Long, generation: Int, status: SyncStatus, error: String, now: Long): Int
}
