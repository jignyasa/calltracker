package com.example.calltracker.ui.postcall

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.ui.notifications.PostCallNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Offers the log form after a business call. An interface so the worker that calls it stays testable. */
interface PostCallLogOffer {
    fun offer(call: CallRecord)
}

/**
 * Opens [PostCallActivity] over whatever is on screen. Android only lets an app start an activity from the
 * background when the user has allowed it to "Display over other apps"; without that, a notification with
 * "Add notes" does the same job one tap later.
 */
@Singleton
class PostCallLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: PostCallNotifier,
) : PostCallLogOffer {

    override fun offer(call: CallRecord) {
        if (canPopUp(context)) {
            try {
                context.startActivity(PostCallActivity.intent(context, call.id))
                notifier.cancel(call.id)
                return
            } catch (_: RuntimeException) {
                // Some OEMs still block background starts; fall through to the notification.
            }
        }
        notifier.showLogPrompt(call)
    }

    companion object {
        fun canPopUp(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun overlaySettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.fromParts("package", context.packageName, null))
    }
}
