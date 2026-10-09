package com.example.calltracker.ui.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.calltracker.R
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.ui.MainActivity
import com.example.calltracker.ui.common.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Was this a work call?" after a call the rule engine couldn't classify. Business / Personal are answered in place
 * by [PostCallActionReceiver]; Log opens the app to fill in the call's details.
 * The lock screen only shows a generic version: the number stays hidden until the phone is unlocked.
 */
@Singleton
class PostCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.notification_channel_post_call))
                .setDescription(context.getString(R.string.notification_channel_post_call_description))
                .build(),
        )
    }

    fun show(call: CallRecord) {
        if (!canNotify()) return
        val subtitle = context.getString(
            R.string.call_subtitle_short,
            context.getString(Formatters.callTypeLabel(call.type)),
            DateUtils.formatDateTime(context, call.startedAt, DateUtils.FORMAT_SHOW_TIME),
        )
        val publicVersion = baseBuilder()
            .setContentTitle(context.getString(R.string.post_call_title))
            .setContentText(context.getString(R.string.post_call_public_text))
            .build()

        val notification = baseBuilder()
            .setContentTitle(context.getString(R.string.post_call_title))
            .setContentText(context.getString(R.string.post_call_text, Formatters.displayName(context, call), subtitle))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openApp(call.id, logCall = false))
            .addAction(0, context.getString(R.string.classification_business), classify(call.id, Classification.BUSINESS))
            .addAction(0, context.getString(R.string.classification_personal), classify(call.id, Classification.PERSONAL))
            .addAction(0, context.getString(R.string.post_call_action_log), openApp(call.id, logCall = true))
            .setWhen(call.startedAt)
            .build()

        try {
            manager.notify(NOTIFICATION_TAG, call.id.notificationId(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    fun cancel(callId: Long) = manager.cancel(NOTIFICATION_TAG, callId.notificationId())

    private fun baseBuilder() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_call)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)

    private fun canNotify(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun classify(callId: Long, classification: Classification): PendingIntent {
        val intent = Intent(context, PostCallActionReceiver::class.java)
            .setAction(PostCallActionReceiver.ACTION_CLASSIFY)
            .putExtra(PostCallActionReceiver.EXTRA_CALL_ID, callId)
            .putExtra(PostCallActionReceiver.EXTRA_CLASSIFICATION, classification.name)
        return PendingIntent.getBroadcast(context, requestCode(callId, classification.ordinal), intent, FLAGS)
    }

    private fun openApp(callId: Long, logCall: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (logCall) MainActivity.ACTION_LOG_CALL else Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode(callId, if (logCall) 10 else 11), intent, FLAGS)
    }

    /** Distinct per call and action, so one notification's intents never replace another's. */
    private fun requestCode(callId: Long, action: Int) = (callId * 16 + action).toInt()

    private fun Long.notificationId() = toInt()

    companion object {
        const val CHANNEL_ID = "post_call"
        private const val NOTIFICATION_TAG = "post_call"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
