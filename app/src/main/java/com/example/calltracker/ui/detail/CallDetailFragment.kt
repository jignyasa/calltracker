package com.example.calltracker.ui.detail

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentCallDetailBinding
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.ui.common.CallVisuals
import com.example.calltracker.ui.common.FollowUpDatePicker
import com.example.calltracker.ui.common.Formatters
import com.example.calltracker.ui.common.OutcomeChoices
import com.example.calltracker.ui.navigator
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallDetailFragment : Fragment(R.layout.fragment_call_detail) {

    private val viewModel: CallDetailViewModel by viewModels()
    private var binding: FragmentCallDetailBinding? = null

    /** True while the screen writes state into its own widgets, so their listeners don't echo it back. */
    private var rendering = false
    private var renderedFormVersion = -1

    /** Set when the call was just made professional from the classify dialog; cleared once the form is in view. */
    private var scrollToLogForm = false

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
        bindDialogs(binding)

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

        binding.outcome.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, OutcomeChoices.labels(requireContext())))
        binding.outcome.setOnItemClickListener { _, _, position, _ -> viewModel.onOutcomeChanged(OutcomeChoices.values[position]) }

        binding.followUp.setOnClickListener {
            FollowUpDatePicker.show(childFragmentManager, viewModel.state.value.draft.followUpAt, viewModel::onFollowUpChanged)
        }
        binding.followUpLayout.setEndIconOnClickListener { viewModel.onFollowUpChanged(null) }
        binding.saveButton.setOnClickListener { viewModel.save() }
        binding.retryUploadButton.setOnClickListener { viewModel.retryUpload() }
    }

    /** Create lead, Classify call and Call saved all answer through the Fragment Result API (rotation-safe). */
    private fun bindDialogs(binding: FragmentCallDetailBinding) {
        binding.createLeadButton.setOnClickListener {
            val call = viewModel.state.value.call ?: return@setOnClickListener
            CreateLeadDialogFragment.newInstance(call.deviceContactName, call.rawNumber)
                .show(childFragmentManager, CreateLeadDialogFragment.TAG)
        }
        childFragmentManager.setFragmentResultListener(CreateLeadDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            viewModel.createLead(
                result.getString(CreateLeadDialogFragment.RESULT_NAME).orEmpty(),
                result.getString(CreateLeadDialogFragment.RESULT_COMPANY),
            )
        }
        childFragmentManager.setFragmentResultListener(ClassifyCallDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val classification = result.getString(ClassifyCallDialogFragment.RESULT_CLASSIFICATION)?.let(Classification::valueOf)
                ?: return@setFragmentResultListener
            // Professional goes straight to logging: the form appears on this screen, so bring it into view.
            scrollToLogForm = classification == Classification.BUSINESS
            viewModel.classify(classification, result.getBoolean(ClassifyCallDialogFragment.RESULT_APPLY_TO_NUMBER))
        }
        childFragmentManager.setFragmentResultListener(CallSavedDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            if (result.getBoolean(CallSavedDialogFragment.RESULT_VIEW_ACTIVITIES)) navigator.showCallList()
        }
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
            CallVisuals.bindAvatar(binding.avatar, name, sizeDp = 56)
            binding.who.text = CallVisuals.whoLine(context, call)
            binding.number.text = call.rawNumber
            binding.number.isVisible = !call.rawNumber.isNullOrBlank() && call.rawNumber != name
            CallVisuals.applyPill(binding.classificationPill, CallVisuals.classificationPill(call.classification), withIcon = false)
            renderMeta(binding, call)
            renderCrm(binding, state)

            when (call.classification) {
                Classification.BUSINESS -> binding.classificationToggle.check(R.id.businessButton)
                Classification.PERSONAL -> binding.classificationToggle.check(R.id.personalButton)
                Classification.UNCLASSIFIED -> binding.classificationToggle.clearChecked()
            }
            binding.source.setText(Formatters.classificationSourceLabel(call.classificationSource))
            binding.applyToNumber.isVisible = call.normalizedNumber != null

            binding.logHint.isVisible = !state.canLog
            binding.logForm.isVisible = state.canLog
            if (scrollToLogForm && state.canLog) {
                scrollToLogForm = false
                binding.scroll.post { binding.scroll.smoothScrollTo(0, binding.logForm.top) }
            }
            if (state.formVersion != renderedFormVersion) {
                renderedFormVersion = state.formVersion
                fillForm(binding, state.draft)
            }
            renderFollowUp(binding, state.draft.followUpAt)
            renderErrors(binding, state.invalidFields)
            binding.saveButton.isEnabled = state.dirty && !state.saving
            binding.retryUploadButton.isVisible = state.canRetryUpload && !state.dirty

            val activity = state.activity
            binding.syncStatus.text = if (activity?.syncStatus == SyncStatus.FAILED_PERMANENT && activity.lastSyncError != null) {
                getString(R.string.sync_failed_reason, activity.lastSyncError)
            } else {
                getString(Formatters.syncStatusLabel(activity?.syncStatus))
            }
            renderSyncIcon(binding, activity?.syncStatus)
            askForClassificationOnce(call)
        } finally {
            rendering = false
        }
    }

    private fun renderMeta(binding: FragmentCallDetailBinding, call: CallRecord) {
        val context = requireContext()
        val type = CallVisuals.callTypePill(call.type)
        binding.metaType.setText(type.label)
        binding.metaType.setCompoundDrawablesRelativeWithIntrinsicBounds(type.icon ?: 0, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(binding.metaType, ContextCompat.getColorStateList(context, type.foreground))
        binding.metaDate.text = Formatters.dayTime(context, call.startedAt)
        binding.metaDuration.text = Formatters.duration(context, call.durationSec)
        binding.metaSim.isVisible = call.simSlot != null
        binding.metaSim.text = call.simSlot?.let { getString(R.string.detail_sim, it + 1) }
    }

    /** The CRM record the call is linked to, or (no match) the offer to create a lead. */
    private fun renderCrm(binding: FragmentCallDetailBinding, state: CallDetailUiState) {
        val crm = state.call?.crmMatch
        binding.crmCard.isVisible = crm != null
        binding.noCrmCard.isVisible = crm == null
        if (crm != null) {
            binding.crmName.text = crm.name
            CallVisuals.bindAvatar(binding.crmAvatar, crm.name)
            val kind = getString(if (crm.type == CrmRecordType.LEAD) R.string.crm_kind_lead else R.string.crm_kind_contact)
            binding.crm.text = crm.company?.let { getString(R.string.detail_crm, kind, it) } ?: kind
        } else {
            binding.noCrmText.setText(
                when {
                    state.canCreateLead -> R.string.detail_no_crm_match
                    state.call?.normalizedNumber == null -> R.string.lead_no_number
                    else -> R.string.detail_no_crm_personal
                },
            )
        }
        binding.createLeadButton.isVisible = state.canCreateLead
        binding.createLeadButton.isEnabled = !state.creatingLead
    }

    private fun renderSyncIcon(binding: FragmentCallDetailBinding, status: SyncStatus?) {
        val pill = CallVisuals.syncPill(status)
        binding.syncStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(pill?.icon ?: R.drawable.ic_shield, 0, 0, 0)
        val tint = pill?.let { ContextCompat.getColorStateList(requireContext(), it.foreground) }
            ?: ColorStateList.valueOf(MaterialColors.getColor(binding.syncStatus, com.google.android.material.R.attr.colorOnSurfaceVariant))
        TextViewCompat.setCompoundDrawableTintList(binding.syncStatus, tint)
    }

    /** Opened from a "Work call?" notification: ask once, if the call still isn't decided. */
    private fun askForClassificationOnce(call: CallRecord) {
        val args = requireArguments()
        if (!args.getBoolean(ARG_ASK_CLASSIFICATION)) return
        args.putBoolean(ARG_ASK_CLASSIFICATION, false)
        if (call.classification != Classification.UNCLASSIFIED) return
        if (childFragmentManager.findFragmentByTag(ClassifyCallDialogFragment.TAG) != null) return
        val line = listOf(
            call.rawNumber?.takeIf { it.isNotBlank() && !it.startsWith("-") } ?: getString(R.string.call_private_number),
            getString(Formatters.callTypeLabel(call.type)),
            Formatters.dayTime(requireContext(), call.startedAt),
        ).joinToString(" · ")
        ClassifyCallDialogFragment.newInstance(line).show(childFragmentManager, ClassifyCallDialogFragment.TAG)
    }

    private fun fillForm(binding: FragmentCallDetailBinding, log: ActivityLog) {
        binding.notes.setText(log.notes)
        binding.nextAction.setText(log.nextAction)
        binding.remarks.setText(log.remarks)
        binding.outcome.setText(OutcomeChoices.label(requireContext(), log.outcome), false)
    }

    private fun renderFollowUp(binding: FragmentCallDetailBinding, followUpAt: Long?) {
        binding.followUp.setText(followUpAt?.let { Formatters.date(requireContext(), it) })
        binding.followUpLayout.isEndIconVisible = followUpAt != null
    }

    private fun renderErrors(binding: FragmentCallDetailBinding, invalid: Set<SaveActivityLogUseCase.Field>) {
        val tooLong = resources.getQuantityString(R.plurals.detail_too_long, ActivityLog.MAX_TEXT_LENGTH, ActivityLog.MAX_TEXT_LENGTH)
        binding.notesLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.NOTES in invalid }
        binding.nextActionLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.NEXT_ACTION in invalid }
        binding.remarksLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.REMARKS in invalid }
    }

    /** "Call Activity Saved!" with a summary of what was just saved. */
    private fun showSaved(state: CallDetailUiState) {
        val call = state.call ?: return
        val context = requireContext()
        val log = state.activity?.log ?: state.draft
        CallSavedDialogFragment.newInstance(
            name = Formatters.displayName(context, call),
            who = CallVisuals.whoLine(context, call),
            callType = getString(Formatters.callTypeLabel(call.type)),
            duration = Formatters.duration(context, call.durationSec),
            outcome = log.outcome?.let { OutcomeChoices.label(context, it) },
            followUp = log.followUpAt?.let { Formatters.date(context, it) },
            nextAction = log.nextAction,
        ).show(childFragmentManager, CallSavedDialogFragment.TAG)
    }

    private fun onEvent(binding: FragmentCallDetailBinding, event: CallDetailEvent) {
        val message = when (event) {
            CallDetailEvent.Saved -> {
                showSaved(viewModel.state.value)
                return
            }
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
            CallDetailEvent.RetryQueued -> getString(R.string.detail_retry_queued)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val ARG_ASK_CLASSIFICATION = "ask_classification"

        /** [askClassification]: show the classify dialog on top if the call is still unsorted. */
        fun newInstance(callId: Long, askClassification: Boolean = false) = CallDetailFragment().apply {
            arguments = bundleOf(CallDetailViewModel.ARG_CALL_ID to callId, ARG_ASK_CLASSIFICATION to askClassification)
        }
    }
}
