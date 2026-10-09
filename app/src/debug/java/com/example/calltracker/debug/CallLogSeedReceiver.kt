package com.example.calltracker.debug

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Debug-only test aid: inserts or removes fake CallLog rows for the sweep scenarios on a real device, where
 * `adb shell content insert` is denied. Guarded by the DUMP permission, so only adb can send it.
 * Unlike `am instrument`, a broadcast doesn't force-stop the app, so trigger behaviour stays realistic.
 *
 * adb shell pm grant com.example.calltracker android.permission.WRITE_CALL_LOG
 * adb shell am broadcast -n com.example.calltracker/.debug.CallLogSeedReceiver --es seed types
 *
 * Actions: types, old, contact, late, bulk (`--ei count N --ei base B`), count (`--ei base B`), cleanup. Every fake number starts with [FAKE_PREFIX].
 */
class CallLogSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Hundreds of provider inserts on the main thread ANR the app, so do the work off it.
        val pending = goAsync()
        thread(name = TAG) {
            try {
                seed(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun seed(context: Context, intent: Intent) {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val rows = mutableListOf<ContentValues>()
        fun add(number: String, type: Int, date: Long, duration: Long = 0, presentation: Int = CallLog.Calls.PRESENTATION_ALLOWED, name: String? = null) {
            rows += ContentValues().apply {
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.DATE, date)
                put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.NUMBER_PRESENTATION, presentation)
                put(CallLog.Calls.NEW, 0)
                name?.let { put(CallLog.Calls.CACHED_NAME, it) }
            }
        }

        when (val action = intent.getStringExtra("seed")) {
            "types" -> {
                add("${FAKE_PREFIX}101", CallLog.Calls.BLOCKED_TYPE, now - 50_000)
                // VOICEMAIL rows can't be written (or read) through CONTENT_URI without allow_voicemails, so none here.
                add("${FAKE_PREFIX}103", CallLog.Calls.ANSWERED_EXTERNALLY_TYPE, now - 40_000, duration = 30)
                add("${FAKE_PREFIX}104", CallLog.Calls.REJECTED_TYPE, now - 30_000)
                add("${FAKE_PREFIX}105", CallLog.Calls.INCOMING_TYPE, now - 20_000, duration = 3_725)
                add("", CallLog.Calls.MISSED_TYPE, now - 10_000, PRIVATE_MARKER_DURATION, CallLog.Calls.PRESENTATION_RESTRICTED)
            }
            "old" -> {
                add("${FAKE_PREFIX}201", CallLog.Calls.MISSED_TYPE, now - TimeUnit.DAYS.toMillis(3))
                add("${FAKE_PREFIX}202", CallLog.Calls.MISSED_TYPE, now - TimeUnit.DAYS.toMillis(10))
            }
            // The dialer fills CACHED_NAME for saved contacts; the sweep reads that, so no real contact is needed.
            "contact" -> add("${FAKE_PREFIX}401", CallLog.Calls.OUTGOING_TYPE, now - 5_000, duration = 42, name = "Test Contact")
            "late" -> add("${FAKE_PREFIX}301", CallLog.Calls.INCOMING_TYPE, now - TimeUnit.DAYS.toMillis(1), duration = 12)
            "bulk" -> {
                // Keep count ≤ ~200 per broadcast: inserts are slow on some OEMs and the broadcast times out at 60 s.
                val count = intent.getIntExtra("count", 200)
                val base = intent.getIntExtra("base", 5000)
                repeat(count) { i ->
                    add("$FAKE_PREFIX${(base + i).toString().padStart(5, '0')}", CallLog.Calls.MISSED_TYPE, now - (count - i) * 1_000L)
                }
            }
            "count" -> {
                val base = intent.getIntExtra("base", 5000)
                val n = resolver.query(
                    CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
                    "${CallLog.Calls.NUMBER} BETWEEN ? AND ?",
                    arrayOf("$FAKE_PREFIX${base.toString().padStart(5, '0')}", "$FAKE_PREFIX${(base + 999).toString().padStart(5, '0')}"),
                    null,
                )?.use { it.count }
                Log.i(TAG, "count from base $base: $n")
                return
            }
            "cleanup" -> {
                val deleted = resolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls.NUMBER} LIKE ? OR (${CallLog.Calls.NUMBER} = '' AND ${CallLog.Calls.DURATION} = ?)",
                    arrayOf("$FAKE_PREFIX%", PRIVATE_MARKER_DURATION.toString()),
                )
                Log.i(TAG, "deleted $deleted rows")
                return
            }
            else -> {
                Log.w(TAG, "unknown action $action")
                return
            }
        }
        val inserted = resolver.bulkInsert(CallLog.Calls.CONTENT_URI, rows.toTypedArray())
        Log.i(TAG, "inserted $inserted rows")
    }

    private companion object {
        const val TAG = "CallLogSeedReceiver"

        /** Not a real Indian mobile range in use; every fake row starts with it. */
        const val FAKE_PREFIX = "+9190000"

        /** The fake private call has no number; this unlikely duration is what lets cleanup find it. */
        const val PRIVATE_MARKER_DURATION = 4_321L
    }
}
