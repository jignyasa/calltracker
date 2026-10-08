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
}
