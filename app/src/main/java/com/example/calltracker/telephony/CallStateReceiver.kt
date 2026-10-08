package com.example.calltracker.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.calltracker.sync.SweepScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Real-time trigger only: when the phone returns to IDLE (a call ended, was missed or rejected) a sweep is queued.
 * Call details are always read from the CallLog, never from this broadcast, because the broadcast carries no
 * duration or call type, arrives twice on some devices, and is easy for OEM battery managers to suppress.
 */
@AndroidEntryPoint
class CallStateReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SweepScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "Phone state: $state")
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            scheduler.requestSweep(SweepScheduler.AFTER_CALL_DELAY_SECONDS)
        }
    }
}

private const val TAG = "CallStateReceiver"
