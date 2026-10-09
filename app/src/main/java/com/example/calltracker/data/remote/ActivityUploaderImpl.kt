package com.example.calltracker.data.remote

import android.util.Log
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.ServerActivity
import com.example.calltracker.domain.model.UploadItemResult
import com.example.calltracker.domain.model.UploadItemStatus
import com.example.calltracker.domain.model.UploadResult
import com.example.calltracker.domain.repository.ActivityUploader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

class ActivityUploaderImpl @Inject constructor(
    private val api: CrmApi,
) : ActivityUploader {

    override suspend fun upload(items: List<ActivityPayload>): UploadResult {
        require(items.size <= ActivityUploader.MAX_BATCH) { "batch of ${items.size} > ${ActivityUploader.MAX_BATCH}" }
        val response = try {
            api.uploadActivities(ActivityBatchRequest(items.map { it.toDto() }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Never log the request: it carries phone numbers and notes.
            return UploadResult.Failed("CRM unreachable (${e.javaClass.simpleName})")
        } catch (e: SerializationException) {
            return UploadResult.Failed("Unreadable CRM response")
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            Log.w(TAG, "Batch upload failed: HTTP ${response.code()}")
            return UploadResult.Failed("CRM error HTTP ${response.code()}")
        }
        return UploadResult.Processed(body.results.mapNotNull { it.toDomain() })
    }

    private fun ActivityPayload.toDto() = ActivityItemDto(
        clientId = clientId,
        phone = phone,
        callType = callType,
        startedAt = startedAt,
        endedAt = endedAt,
        durationSec = durationSec,
        updatedAt = updatedAt,
        simSlot = simSlot,
        leadId = leadId,
        contactId = contactId,
        notes = notes,
        outcome = outcome?.name,
        followUpAt = followUpAt,
        nextAction = nextAction,
        remarks = remarks,
    )

    /** Null (→ treated as missing, so retried) for results without a clientId or with an unknown status. */
    private fun ActivityResultDto.toDomain(): UploadItemResult? {
        val id = clientId ?: return null
        val itemStatus = UploadItemStatus.entries.firstOrNull { it.name == status } ?: return null
        return UploadItemResult(
            clientId = id,
            status = itemStatus,
            serverId = serverId,
            version = version,
            errors = errors,
            server = server?.let {
                ServerActivity(
                    log = ActivityLog(
                        notes = it.notes,
                        outcome = it.outcome?.let { name -> CallOutcome.entries.firstOrNull { o -> o.name == name } },
                        followUpAt = it.followUpAt,
                        nextAction = it.nextAction,
                        remarks = it.remarks,
                    ),
                    updatedAt = it.updatedAt,
                )
            },
        )
    }

    private companion object {
        const val TAG = "ActivityUploader"
    }
}
