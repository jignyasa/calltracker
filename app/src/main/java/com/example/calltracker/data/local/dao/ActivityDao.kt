package com.example.calltracker.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.calltracker.data.local.entity.ActivityEntity
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/** An activity joined with its outbox row (if any). */
data class ActivityWithSync(
    @Embedded val activity: ActivityEntity,
    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatus?,
    @ColumnInfo(name = "sync_error") val syncError: String?,
)

@Dao
interface ActivityDao {

    @Query(
        "SELECT a.*, o.status AS sync_status, o.last_error AS sync_error FROM activities a " +
            "LEFT JOIN outbox o ON o.call_id = a.call_id WHERE a.call_id = :callId",
    )
    fun observe(callId: Long): Flow<ActivityWithSync?>

    @Query(
        "SELECT a.*, o.status AS sync_status, o.last_error AS sync_error FROM activities a " +
            "LEFT JOIN outbox o ON o.call_id = a.call_id WHERE a.call_id = :callId",
    )
    suspend fun get(callId: Long): ActivityWithSync?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(activity: ActivityEntity): Long

    @Query(
        "UPDATE activities SET notes = :notes, outcome = :outcome, follow_up_at = :followUpAt, " +
            "next_action = :nextAction, remarks = :remarks, updated_at = :now WHERE call_id = :callId",
    )
    suspend fun updateLog(
        callId: Long,
        notes: String?,
        outcome: CallOutcome?,
        followUpAt: Long?,
        nextAction: String?,
        remarks: String?,
        now: Long,
    ): Int

    @Query("UPDATE activities SET server_id = :serverId, server_version = :version WHERE call_id = :callId")
    suspend fun setServerIdentity(callId: Long, serverId: String, version: Int?)

    /** Server copy wins a conflict: its log and timestamp replace ours; the server id is kept if none came back. */
    @Query(
        "UPDATE activities SET notes = :notes, outcome = :outcome, follow_up_at = :followUpAt, next_action = :nextAction, " +
            "remarks = :remarks, updated_at = :updatedAt, server_id = COALESCE(:serverId, server_id), " +
            "server_version = COALESCE(:version, server_version) WHERE call_id = :callId",
    )
    suspend fun applyServerCopy(
        callId: Long,
        notes: String?,
        outcome: CallOutcome?,
        followUpAt: Long?,
        nextAction: String?,
        remarks: String?,
        updatedAt: Long,
        serverId: String?,
        version: Int?,
    )
}
