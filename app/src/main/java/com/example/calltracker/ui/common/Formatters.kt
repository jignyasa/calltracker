package com.example.calltracker.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import com.example.calltracker.R
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus

object Formatters {

    fun relativeTime(context: Context, epochMs: Long): CharSequence =
        DateUtils.getRelativeDateTimeString(
            context,
            epochMs,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.WEEK_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_ALL,
        )

    fun dateTime(context: Context, epochMs: Long): String =
        DateUtils.formatDateTime(
            context,
            epochMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )

    fun date(context: Context, epochMs: Long): String =
        DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY)

    fun duration(context: Context, seconds: Long): String = when {
        seconds < 60 -> context.getString(R.string.duration_seconds, seconds)
        seconds < 3600 -> context.getString(R.string.duration_minutes, seconds / 60, seconds % 60)
        else -> context.getString(R.string.duration_hours, seconds / 3600, (seconds % 3600) / 60)
    }

    /**
     * CRM name, else the device contact name, else the number as dialled. Only real "no number" calls show as
     * private: the CallLog stores those as empty or negative markers (-1 unknown, -2 private, -3 payphone), while
     * short codes like 121 can't be normalised but are still worth showing.
     */
    fun displayName(context: Context, call: CallRecord): String =
        call.crmMatch?.name
            ?: call.deviceContactName
            ?: call.rawNumber?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-") }
            ?: context.getString(R.string.call_private_number)

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

    @StringRes
    fun classificationLabel(classification: Classification): Int = when (classification) {
        Classification.BUSINESS -> R.string.classification_business
        Classification.PERSONAL -> R.string.classification_personal
        Classification.UNCLASSIFIED -> R.string.classification_unclassified
    }

    /** Why the call has its classification, e.g. "In CRM" or "Work SIM". */
    @StringRes
    fun classificationSourceLabel(source: ClassificationSource): Int = when (source) {
        ClassificationSource.USER_MANUAL -> R.string.source_user_manual
        ClassificationSource.USER_RULE -> R.string.source_user_rule
        ClassificationSource.CRM_MATCH -> R.string.source_crm_match
        ClassificationSource.WORK_SIM -> R.string.source_work_sim
        ClassificationSource.DEVICE_CONTACT -> R.string.source_device_contact
        ClassificationSource.NONE -> R.string.source_none
    }

    @StringRes
    fun syncStatusLabel(status: SyncStatus?): Int = when (status) {
        null -> R.string.sync_not_queued
        SyncStatus.PENDING, SyncStatus.FAILED_RETRYABLE -> R.string.sync_pending
        SyncStatus.IN_FLIGHT -> R.string.sync_in_flight
        SyncStatus.SYNCED -> R.string.sync_synced
        SyncStatus.FAILED_PERMANENT -> R.string.sync_failed
    }

    @StringRes
    fun outcomeLabel(outcome: CallOutcome): Int = when (outcome) {
        CallOutcome.INTERESTED -> R.string.outcome_interested
        CallOutcome.NOT_INTERESTED -> R.string.outcome_not_interested
        CallOutcome.CALL_BACK -> R.string.outcome_call_back
        CallOutcome.NO_ANSWER -> R.string.outcome_no_answer
        CallOutcome.WRONG_NUMBER -> R.string.outcome_wrong_number
        CallOutcome.CONVERTED -> R.string.outcome_converted
        CallOutcome.OTHER -> R.string.outcome_other
    }
}
