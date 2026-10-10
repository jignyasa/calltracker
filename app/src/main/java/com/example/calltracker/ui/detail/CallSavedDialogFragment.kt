package com.example.calltracker.ui.detail

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.setFragmentResult
import com.example.calltracker.R
import com.example.calltracker.databinding.DialogCallSavedBinding
import com.example.calltracker.databinding.ItemSummaryRowBinding
import com.example.calltracker.ui.common.CallVisuals
import com.example.calltracker.ui.common.FullScreenDialogFragment
import com.google.android.material.color.MaterialColors

/**
 * "Call Activity Saved!": shown after a log is saved, with what was saved. "View in Activities" goes back to the
 * call list; back (or "Back to call") returns to the call. Everything it shows comes in its arguments.
 */
class CallSavedDialogFragment : FullScreenDialogFragment(R.layout.dialog_call_saved) {

    override val lightStatusBarIcons: Boolean = false

    override fun topInsetView(view: View): View = view
    override fun bottomInsetView(view: View): View = DialogCallSavedBinding.bind(view).buttons

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = DialogCallSavedBinding.bind(view)
        val args = requireArguments()
        val name = args.getString(ARG_NAME).orEmpty()
        binding.name.text = name
        binding.who.text = args.getString(ARG_WHO)
        CallVisuals.bindAvatar(binding.avatar, name, sizeDp = 44)

        addRow(binding, R.drawable.ic_phone, R.string.saved_call_type, args.getString(ARG_CALL_TYPE))
        addRow(binding, R.drawable.ic_schedule, R.string.saved_duration, args.getString(ARG_DURATION))
        addRow(binding, R.drawable.ic_check_circle, R.string.detail_outcome, args.getString(ARG_OUTCOME))
        addRow(binding, R.drawable.ic_event, R.string.detail_follow_up, args.getString(ARG_FOLLOW_UP))
        addRow(binding, R.drawable.ic_auto_awesome, R.string.detail_next_action, args.getString(ARG_NEXT_ACTION))

        binding.viewActivitiesButton.setOnClickListener {
            setFragmentResult(REQUEST_KEY, bundleOf(RESULT_VIEW_ACTIVITIES to true))
            dismiss()
        }
        binding.backButton.setOnClickListener { dismiss() }
    }

    private fun addRow(binding: DialogCallSavedBinding, @DrawableRes icon: Int, @StringRes label: Int, value: String?) {
        if (value.isNullOrBlank()) return
        val row = ItemSummaryRowBinding.inflate(layoutInflater, binding.rows, true)
        row.label.setText(label)
        row.label.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(
            row.label,
            ColorStateList.valueOf(MaterialColors.getColor(row.label, com.google.android.material.R.attr.colorOnSurfaceVariant)),
        )
        row.value.text = value
    }

    companion object {
        const val TAG = "call_saved"
        const val REQUEST_KEY = "call_saved_result"
        const val RESULT_VIEW_ACTIVITIES = "view_activities"
        private const val ARG_NAME = "name"
        private const val ARG_WHO = "who"
        private const val ARG_CALL_TYPE = "call_type"
        private const val ARG_DURATION = "duration"
        private const val ARG_OUTCOME = "outcome"
        private const val ARG_FOLLOW_UP = "follow_up"
        private const val ARG_NEXT_ACTION = "next_action"

        fun newInstance(
            name: String,
            who: String,
            callType: String,
            duration: String,
            outcome: String?,
            followUp: String?,
            nextAction: String?,
        ) = CallSavedDialogFragment().apply {
            arguments = bundleOf(
                ARG_NAME to name,
                ARG_WHO to who,
                ARG_CALL_TYPE to callType,
                ARG_DURATION to duration,
                ARG_OUTCOME to outcome,
                ARG_FOLLOW_UP to followUp,
                ARG_NEXT_ACTION to nextAction,
            )
        }
    }
}
