package com.example.calltracker.data.calllog

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.example.calltracker.di.IoDispatcher
import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.repository.CallLogAccessDeniedException
import com.example.calltracker.domain.repository.CallLogSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AndroidCallLogSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CallLogSource {

    override suspend fun read(
        watermark: SweepWatermark,
        overlapMs: Long,
        afterId: Long,
        limit: Int,
    ): List<CallLogEntry> = withContext(io) {
        if (!granted(Manifest.permission.READ_CALL_LOG)) throw CallLogAccessDeniedException()

        val selection = "(${CallLog.Calls._ID} > ? OR ${CallLog.Calls.DATE} > ?) AND ${CallLog.Calls._ID} > ?"
        val args = arrayOf(
            watermark.lastCallLogId.toString(),
            (watermark.lastStartedAt - overlapMs).toString(),
            afterId.toString(),
        )
        val queryArgs = bundleOf(
            ContentResolver.QUERY_ARG_SQL_SELECTION to selection,
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS to args,
            ContentResolver.QUERY_ARG_SQL_SORT_ORDER to "${CallLog.Calls._ID} ASC",
            ContentResolver.QUERY_ARG_LIMIT to limit,
        )
        val slots = simSlotsByAccountId()

        try {
            query(queryArgs) { cursor ->
                val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val account = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)

                buildList {
                    // Some OEM providers ignore QUERY_ARG_LIMIT; never take more than a page.
                    while (size < limit && cursor.moveToNext()) {
                        val accountId = cursor.getStringOrNull(account)
                        add(
                            CallLogEntry(
                                callLogId = cursor.getLong(id),
                                rawNumber = cursor.getStringOrNull(number),
                                cachedName = cursor.getStringOrNull(name),
                                type = mapType(cursor.getInt(type)),
                                startedAt = cursor.getLong(date),
                                durationSec = cursor.getLong(duration),
                                phoneAccountId = accountId,
                                simSlot = accountId?.let { slots[it] },
                            ),
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            throw CallLogAccessDeniedException(e)
        }
    }

    private fun <T> query(args: Bundle, read: (android.database.Cursor) -> List<T>): List<T> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        return context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, args, null)
            ?.use(read)
            .orEmpty()
    }

    /**
     * Maps CallLog `PHONE_ACCOUNT_ID` to a SIM slot. OEMs store either the subscription id or the ICCID there,
     * so both are indexed. Empty when READ_PHONE_STATE is missing.
     */
    private fun simSlotsByAccountId(): Map<String, Int> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyMap()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyMap()
        val subscriptions = try {
            manager.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return emptyMap()
        }
        return buildMap {
            for (info in subscriptions) {
                put(info.subscriptionId.toString(), info.simSlotIndex)
                @Suppress("DEPRECATION")
                info.iccId?.takeIf { it.isNotBlank() }?.let { put(it, info.simSlotIndex) }
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        fun mapType(type: Int): CallType = when (type) {
            CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
            CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
            CallLog.Calls.MISSED_TYPE -> CallType.MISSED
            CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
            CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
            CallLog.Calls.VOICEMAIL_TYPE -> CallType.VOICEMAIL
            // ANSWERED_EXTERNALLY_TYPE and OEM-specific values.
            else -> CallType.OTHER
        }
    }
}
