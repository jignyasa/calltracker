package com.example.calltracker.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import com.example.calltracker.R
import com.example.calltracker.domain.model.CallType

object Formatters {

    fun relativeTime(context: Context, epochMs: Long): CharSequence =
        DateUtils.getRelativeDateTimeString(
            context,
            epochMs,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.WEEK_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_ALL,
        )

    fun duration(context: Context, seconds: Long): String =
        if (seconds < 60) {
            context.getString(R.string.duration_seconds, seconds)
        } else {
            context.getString(R.string.duration_minutes, seconds / 60, seconds % 60)
        }

    @StringRes
    fun callTypeLabel(type: CallType): Int = when (type) {
        CallType.INCOMING -> R.string.call_type_incoming
        CallType.OUTGOING -> R.string.call_type_outgoing
        CallType.MISSED -> R.string.call_type_missed
        CallType.REJECTED -> R.string.call_type_rejected
        CallType.BLOCKED -> R.string.call_type_blocked
        CallType.VOICEMAIL -> R.string.call_type_voicemail
        CallType.OTHER -> R.string.call_type_other
    }
}
