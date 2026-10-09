package com.example.calltracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus

/** Enums are stored by name (Room's built-in enum converter); renaming a constant needs a migration. */
@Entity(
    tableName = "calls",
    indices = [
        Index(value = ["call_log_id", "started_at"], unique = true),
        Index(value = ["normalized_number"]),
        Index(value = ["started_at"]),
    ],
)
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "call_log_id") val callLogId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "raw_number") val rawNumber: String?,
    @ColumnInfo(name = "normalized_number") val normalizedNumber: String?,
    @ColumnInfo(name = "device_contact_name") val deviceContactName: String?,
    @ColumnInfo(name = "type") val type: CallType,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "phone_account_id") val phoneAccountId: String?,
    @ColumnInfo(name = "sim_slot") val simSlot: Int?,
    @ColumnInfo(name = "classification") val classification: Classification,
    @ColumnInfo(name = "classification_source") val classificationSource: ClassificationSource,
    @ColumnInfo(name = "crm_type") val crmType: CrmRecordType?,
    @ColumnInfo(name = "crm_id") val crmId: String?,
    @ColumnInfo(name = "crm_name") val crmName: String?,
    @ColumnInfo(name = "crm_company") val crmCompany: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One CRM activity per BUSINESS call. */
@Entity(
    tableName = "activities",
    foreignKeys = [
        ForeignKey(entity = CallEntity::class, parentColumns = ["id"], childColumns = ["call_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["client_id"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey @ColumnInfo(name = "call_id") val callId: Long,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "outcome") val outcome: CallOutcome?,
    @ColumnInfo(name = "follow_up_at") val followUpAt: Long?,
    @ColumnInfo(name = "next_action") val nextAction: String?,
    @ColumnInfo(name = "remarks") val remarks: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "server_version") val serverVersion: Int?,
)

/**
 * Sync outbox: one row per activity that must reach the CRM.
 *
 * [generation] is bumped every time the item is (re)enqueued. A worker remembers the generation it sent and only
 * marks the row SYNCED if it's unchanged, so an edit made while an upload was in flight is never lost.
 */
@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(entity = CallEntity::class, parentColumns = ["id"], childColumns = ["call_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["client_id"], unique = true), Index(value = ["status"])],
)
data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "call_id") val callId: Long,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "status") val status: SyncStatus,
    @ColumnInfo(name = "generation") val generation: Int,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "number_rules")
data class NumberRuleEntity(
    @PrimaryKey @ColumnInfo(name = "normalized_number") val normalizedNumber: String,
    @ColumnInfo(name = "classification") val classification: Classification,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** CRM lookup cache. A row with null [crmType] records a confirmed "not in CRM" answer. */
@Entity(tableName = "crm_cache")
data class CrmCacheEntity(
    @PrimaryKey @ColumnInfo(name = "normalized_number") val normalizedNumber: String,
    @ColumnInfo(name = "crm_type") val crmType: CrmRecordType?,
    @ColumnInfo(name = "crm_id") val crmId: String?,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "company") val company: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** Single-row table holding the CallLog sweep watermark. */
@Entity(tableName = "sweep_state")
data class SweepStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "last_call_log_id") val lastCallLogId: Long,
    @ColumnInfo(name = "last_started_at") val lastStartedAt: Long,
    @ColumnInfo(name = "last_sweep_at") val lastSweepAt: Long,
    /** Backfill floor; CallLog rows that started earlier are never imported. Default only for MIGRATION_1_2. */
    @ColumnInfo(name = "not_before", defaultValue = "0") val notBefore: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
