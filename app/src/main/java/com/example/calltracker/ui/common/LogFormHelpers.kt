package com.example.calltracker.ui.common

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.example.calltracker.R
import com.example.calltracker.domain.model.CallOutcome
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The outcome dropdown, shared by the call detail screen and the post-call pop-up. "No outcome" first. */
object OutcomeChoices {
    val values: List<CallOutcome?> = listOf<CallOutcome?>(null) + CallOutcome.entries

    fun labels(context: Context): List<String> = values.map { label(context, it) }

    fun label(context: Context, outcome: CallOutcome?): String =
        outcome?.let { context.getString(Formatters.outcomeLabel(it)) } ?: context.getString(R.string.detail_outcome_none)
}

/**
 * Follow-ups are dates: the picker works in UTC midnights, the app stores the local start of that day.
 * Past dates can't be picked.
 */
object FollowUpDatePicker {
    fun show(fragmentManager: FragmentManager, current: Long?, onPicked: (Long) -> Unit) {
        val zone = ZoneId.systemDefault()
        val initial = current?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now(zone)
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.detail_follow_up)
            .setSelection(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build())
            .build()
        picker.addOnPositiveButtonClickListener { utcMidnight ->
            val date = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
            onPicked(date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        picker.show(fragmentManager, "follow_up")
    }
}
