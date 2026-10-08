package com.example.calltracker.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.calltracker.sync.SweepScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** After a reboot or app update: catch up on calls made meanwhile and re-arm the triggers. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SweepScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                scheduler.ensureScheduled()
                scheduler.requestSweep()
            }
        }
    }
}
