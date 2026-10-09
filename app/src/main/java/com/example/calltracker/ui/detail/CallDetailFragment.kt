package com.example.calltracker.ui.detail

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentCallDetailBinding
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.ui.common.Formatters
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@AndroidEntryPoint
class CallDetailFragment : Fragment(R.layout.fragment_call_detail) {

    private val viewModel: CallDetailViewModel by viewModels()
    private var binding: FragmentCallDetailBinding? = null

    /** True while the screen writes state into its own widgets, so their listeners don't echo it back. */
    private var rendering = false
    private var renderedFormVersion = -1

    private val outcomes = listOf<CallOutcome?>(null) + CallOutcome.entries

    private val unsavedChangesGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.detail_unsaved_title)
                .setMessage(R.string.detail_unsaved_message)
                .setPositiveButton(R.string.detail_discard) { _, _ ->
                    viewModel.discardChanges()
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                .setNegativeButton(R.string.detail_keep_editing, null)
                .show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCallDetailBinding.bind(view).also { binding = it }
        requireActivity().setTitle(R.string.title_call_detail)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, unsavedChangesGuard)
        renderedFormVersion = -1

        bindClassification(binding)
        bindForm(binding)
        binding.createLeadButton.setOnClickListener {
            val call = viewModel.state.value.call ?: return@setOnClickListener
            CreateLeadDialogFragment.newInstance(call.deviceContactName).show(childFragmentManager, CreateLeadDialogFragment.TAG)
        }
        childFragmentManager.setFragmentResultListener(CreateLeadDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            viewModel.createLead(
                result.getString(CreateLeadDialogFragment.RESULT_NAME).orEmpty(),
                result.getString(CreateLeadDialogFragment.RESULT_COMPANY),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(binding, it) } }
                launch { viewModel.events.collect { onEvent(binding, it) } }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindClassification(binding: FragmentCallDetailBinding) {
        binding.classificationToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (rendering || !isChecked) return@addOnButtonCheckedListener
            val classification = if (buttonId == R.id.businessButton) Classification.BUSINESS else Classification.PERSONAL
            viewModel.classify(classification, binding.applyToNumber.isChecked)
        }
        // Ticking "always" after choosing applies the current choice to the whole number right away.
        binding.applyToNumber.setOnCheckedChangeListener { _, checked ->
            if (rendering || !checked) return@setOnCheckedChangeListener
            val current = viewModel.state.value.call?.classification ?: return@setOnCheckedChangeListener
            if (current != Classification.UNCLASSIFIED) viewModel.classify(current, applyToNumber = true)
        }
    }

    private fun bindForm(binding: FragmentCallDetailBinding) {
        binding.notes.watch(viewModel::onNotesChanged)
        binding.nextAction.watch(viewModel::onNextActionChanged)
        binding.remarks.watch(viewModel::onRemarksChanged)

        binding.outcome.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                outcomes.map { it?.let { o -> getString(Formatters.outcomeLabel(o)) } ?: getString(R.string.detail_outcome_none) },
            ),
        )
        binding.outcome.setOnItemClickListener { _, _, position, _ -> viewModel.onOutcomeChanged(outcomes[position]) }

        binding.followUp.setOnClickListener { pickFollowUpDate() }
        binding.followUpLayout.setEndIconOnClickListener { viewModel.onFollowUpChanged(null) }
        binding.saveButton.setOnClickListener { viewModel.save() }
    }

    private fun EditText.watch(onChange: (String) -> Unit) = doAfterTextChanged {
        if (!rendering) onChange(it?.toString().orEmpty())
    }

    private fun render(binding: FragmentCallDetailBinding, state: CallDetailUiState) {
        rendering = true
        try {
            binding.progress.isVisible = state.loading
            binding.notFound.isVisible = state.notFound
            binding.scroll.isVisible = !state.loading && !state.notFound
            unsavedChangesGuard.isEnabled = state.dirty
            val call = state.call ?: return
            val context = requireContext()

            val name = Formatters.displayName(context, call)
            binding.name.text = name
            binding.number.text = call.rawNumber
            binding.number.isVisible = !call.rawNumber.isNullOrBlank() && call.rawNumber != name
            binding.meta.text = listOfNotNull(
                getString(Formatters.callTypeLabel(call.type)),
                Formatters.dateTime(context, call.startedAt),
                Formatters.duration(context, call.durationSec),
                call.simSlot?.let { getString(R.string.detail_sim, it + 1) },
            ).joinToString(" · ")
            val crm = call.crmMatch
            binding.crm.isVisible = crm != null
            if (crm != null) {
                val kind = getString(if (crm.type == CrmRecordType.LEAD) R.string.detail_crm_lead else R.string.detail_crm_contact)
                binding.crm.text = crm.company?.let { getString(R.string.detail_crm, kind, it) } ?: kind
            }

            when (call.classification) {
                Classification.BUSINESS -> binding.classificationToggle.check(R.id.businessButton)
                Classification.PERSONAL -> binding.classificationToggle.check(R.id.personalButton)
                Classification.UNCLASSIFIED -> binding.classificationToggle.clearChecked()
            }
            binding.source.setText(Formatters.classificationSourceLabel(call.classificationSource))
            binding.applyToNumber.isVisible = call.normalizedNumber != null
            binding.createLeadButton.isVisible = state.canCreateLead
            binding.createLeadButton.isEnabled = !state.creatingLead

            binding.logHint.isVisible = !state.canLog
            binding.logForm.isVisible = state.canLog
            if (state.formVersion != renderedFormVersion) {
                renderedFormVersion = state.formVersion
                fillForm(binding, state.draft)
            }
            renderFollowUp(binding, state.draft.followUpAt)
            renderErrors(binding, state.invalidFields)
            binding.saveButton.isEnabled = state.dirty && !state.saving

            val activity = state.activity
            binding.syncStatus.text = if (activity?.syncStatus == SyncStatus.FAILED_PERMANENT && activity.lastSyncError != null) {
                getString(R.string.sync_failed_reason, activity.lastSyncError)
            } else {
                getString(Formatters.syncStatusLabel(activity?.syncStatus))
            }
        } finally {
            rendering = false
        }
    }

    private fun fillForm(binding: FragmentCallDetailBinding, log: ActivityLog) {
        binding.notes.setText(log.notes)
        binding.nextAction.setText(log.nextAction)
        binding.remarks.setText(log.remarks)
        val outcomeLabel = log.outcome?.let { getString(Formatters.outcomeLabel(it)) } ?: getString(R.string.detail_outcome_none)
        binding.outcome.setText(outcomeLabel, false)
    }

    private fun renderFollowUp(binding: FragmentCallDetailBinding, followUpAt: Long?) {
        binding.followUp.setText(followUpAt?.let { Formatters.date(requireContext(), it) })
        binding.followUpLayout.isEndIconVisible = followUpAt != null
        binding.followUpLayout.placeholderText = getString(R.string.detail_follow_up_none)
    }

    private fun renderErrors(binding: FragmentCallDetailBinding, invalid: Set<SaveActivityLogUseCase.Field>) {
        val tooLong = resources.getQuantityString(R.plurals.detail_too_long, ActivityLog.MAX_TEXT_LENGTH, ActivityLog.MAX_TEXT_LENGTH)
        binding.notesLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.NOTES in invalid }
        binding.nextActionLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.NEXT_ACTION in invalid }
        binding.remarksLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.REMARKS in invalid }
    }

    /** Follow-ups are dates: the picker works in UTC midnights, stored as the local start of that day. */
    private fun pickFollowUpDate() {
        val zone = ZoneId.systemDefault()
        val current = viewModel.state.value.draft.followUpAt
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: LocalDate.now(zone)
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.detail_follow_up)
            .setSelection(current.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build())
            .build()
        picker.addOnPositiveButtonClickListener { utcMidnight ->
            val date = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
            viewModel.onFollowUpChanged(date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        picker.show(childFragmentManager, "follow_up")
    }

    private fun onEvent(binding: FragmentCallDetailBinding, event: CallDetailEvent) {
        val message = when (event) {
            CallDetailEvent.Saved -> getString(R.string.detail_saved)
            CallDetailEvent.NotBusiness -> getString(R.string.detail_log_hint_not_business)
            CallDetailEvent.AlreadyUploaded -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.detail_already_uploaded)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }
            is CallDetailEvent.LeadCreated -> getString(R.string.lead_created, event.name)
            is CallDetailEvent.LeadAlreadyInCrm -> getString(R.string.lead_already_exists, event.name)
            CallDetailEvent.LeadUnavailable -> getString(R.string.lead_unavailable)
            is CallDetailEvent.LeadRejected -> getString(R.string.lead_rejected, event.reason)
            CallDetailEvent.LeadNameRequired -> getString(R.string.lead_name_required)
            CallDetailEvent.LeadNoNumber -> getString(R.string.lead_no_number)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        fun newInstance(callId: Long) = CallDetailFragment().apply {
            arguments = bundleOf(CallDetailViewModel.ARG_CALL_ID to callId)
        }
    }
}
