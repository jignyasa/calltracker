package com.example.calltracker.ui.postcall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calltracker.R
import com.example.calltracker.databinding.ActivityPostCallBinding
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.ui.MainActivity
import com.example.calltracker.ui.common.FollowUpDatePicker
import com.example.calltracker.ui.common.Formatters
import com.example.calltracker.ui.common.OutcomeChoices
import com.example.calltracker.ui.detail.CallDetailEvent
import com.example.calltracker.ui.detail.CallDetailUiState
import com.example.calltracker.ui.detail.CallDetailViewModel
import com.example.calltracker.ui.detail.CreateLeadDialogFragment
import com.example.calltracker.ui.notifications.PostCallNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The log form that pops up over whatever is on screen right after a business call (needs "Display over other
 * apps"), or when the user answers "Professional" on a "Work call?" notification. Same ViewModel as the call detail
 * screen, so drafts, validation and sync behave exactly the same; only the quick fields are here, the rest is one tap
 * away ("More details"). An unknown number can be made a CRM lead right here.
 */
@AndroidEntryPoint
class PostCallActivity : AppCompatActivity() {

    private val viewModel: CallDetailViewModel by viewModels()
    private lateinit var binding: ActivityPostCallBinding

    @Inject lateinit var notifier: PostCallNotifier

    /** True while the screen writes state into its own widgets, so their listeners don't echo it back. */
    private var rendering = false
    private var renderedFormVersion = -1

    /** Opened by "Professional" on a notification: the call is marked professional first, and the form waits for it. */
    private var markingProfessional = false
    private var professionalRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // A stray tap outside must not throw away what the user typed.
        setFinishOnTouchOutside(false)
        markingProfessional = savedInstanceState?.getBoolean(STATE_MARKING)
            ?: intent.getBooleanExtra(EXTRA_MARK_PROFESSIONAL, false)
        // A notification button doesn't dismiss its notification by itself.
        if (savedInstanceState == null && markingProfessional) notifier.cancel(callId())

        binding.createLeadButton.setOnClickListener {
            val call = viewModel.state.value.call ?: return@setOnClickListener
            CreateLeadDialogFragment.newInstance(call.deviceContactName, call.rawNumber)
                .show(supportFragmentManager, CreateLeadDialogFragment.TAG)
        }
        supportFragmentManager.setFragmentResultListener(CreateLeadDialogFragment.REQUEST_KEY, this) { _, result ->
            viewModel.createLead(
                result.getString(CreateLeadDialogFragment.RESULT_NAME).orEmpty(),
                result.getString(CreateLeadDialogFragment.RESULT_COMPANY),
            )
        }

        binding.notes.watch(viewModel::onNotesChanged)
        binding.outcome.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, OutcomeChoices.labels(this)))
        binding.outcome.setOnItemClickListener { _, _, position, _ -> viewModel.onOutcomeChanged(OutcomeChoices.values[position]) }
        binding.followUp.setOnClickListener {
            FollowUpDatePicker.show(supportFragmentManager, viewModel.state.value.draft.followUpAt, viewModel::onFollowUpChanged)
        }
        binding.followUpLayout.setEndIconOnClickListener { viewModel.onFollowUpChanged(null) }

        binding.skipButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener {
            if (viewModel.state.value.dirty) viewModel.save() else finish()
        }
        binding.moreButton.setOnClickListener {
            // The full screen also has the call's classification, next action, remarks and upload status. A draft
            // typed here is saved first (and only then do we leave, so the save isn't cancelled with this screen).
            if (viewModel.state.value.dirty) {
                openDetailsAfterSave = true
                viewModel.save()
            } else {
                openDetails()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.events.collect { event ->
                        if (event == CallDetailEvent.Saved) {
                            if (openDetailsAfterSave) {
                                openDetails()
                            } else {
                                toast(getString(R.string.popup_saved))
                                finish()
                            }
                        } else {
                            leadMessage(event)?.let(::toast)
                        }
                    }
                }
            }
        }
    }

    private var openDetailsAfterSave = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_MARKING, markingProfessional)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun leadMessage(event: CallDetailEvent): String? = when (event) {
        is CallDetailEvent.LeadCreated -> getString(R.string.lead_created, event.name)
        is CallDetailEvent.LeadAlreadyInCrm -> getString(R.string.lead_already_exists, event.name)
        CallDetailEvent.LeadUnavailable -> getString(R.string.lead_unavailable)
        is CallDetailEvent.LeadRejected -> getString(R.string.lead_rejected, event.reason)
        CallDetailEvent.LeadNameRequired -> getString(R.string.lead_name_required)
        CallDetailEvent.LeadNoNumber -> getString(R.string.lead_no_number)
        else -> null
    }

    private fun openDetails() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_LOG_CALL)
                .putExtra(MainActivity.EXTRA_CALL_ID, callId())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    /** Another call ended while this one was still open: show the newer one (its ViewModel is keyed by the intent). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getLongExtra(CallDetailViewModel.ARG_CALL_ID, -1) != callId()) {
            setIntent(intent)
            recreate()
        }
    }

    private fun callId(): Long = intent.getLongExtra(CallDetailViewModel.ARG_CALL_ID, -1)

    private fun EditText.watch(onChange: (String) -> Unit) = doAfterTextChanged {
        if (!rendering) onChange(it?.toString().orEmpty())
    }

    private fun render(state: CallDetailUiState) {
        if (state.loading) return
        val call = state.call
        if (markingProfessional && call != null) {
            if (call.classification != Classification.BUSINESS) {
                if (!professionalRequested) {
                    professionalRequested = true
                    viewModel.classify(Classification.BUSINESS, applyToNumber = true) // saved for the number
                }
                return // the form appears once the call is professional
            }
            markingProfessional = false
        }
        // Deleted, or no longer business (e.g. the user marked it personal elsewhere): nothing to log here.
        if (state.notFound || call == null || !state.canLog) {
            finish()
            return
        }
        rendering = true
        try {
            binding.name.text = Formatters.displayName(this, call)
            binding.details.text = listOfNotNull(
                call.crmMatch?.company,
                getString(Formatters.callTypeLabel(call.type)),
                Formatters.duration(this, call.durationSec),
            ).joinToString(" · ")
            if (state.formVersion != renderedFormVersion) {
                renderedFormVersion = state.formVersion
                fill(state.draft)
            }
            binding.followUp.setText(state.draft.followUpAt?.let { Formatters.date(this, it) })
            binding.followUpLayout.isEndIconVisible = state.draft.followUpAt != null
            val tooLong = resources.getQuantityString(R.plurals.detail_too_long, ActivityLog.MAX_TEXT_LENGTH, ActivityLog.MAX_TEXT_LENGTH)
            binding.notesLayout.error = tooLong.takeIf { SaveActivityLogUseCase.Field.NOTES in state.invalidFields }
            binding.saveButton.isEnabled = !state.saving
            binding.leadRow.isVisible = state.canCreateLead
            binding.createLeadButton.isEnabled = !state.creatingLead
        } finally {
            rendering = false
        }
    }

    private fun fill(log: ActivityLog) {
        binding.notes.setText(log.notes)
        binding.outcome.setText(OutcomeChoices.label(this, log.outcome), false)
    }

    companion object {
        private const val EXTRA_MARK_PROFESSIONAL = "mark_professional"
        private const val STATE_MARKING = "marking_professional"

        fun intent(context: Context, callId: Long): Intent =
            Intent(context, PostCallActivity::class.java)
                .putExtra(CallDetailViewModel.ARG_CALL_ID, callId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** "Professional" on a "Work call?" notification: mark the call professional, then show the form. */
        fun markProfessionalIntent(context: Context, callId: Long): Intent =
            intent(context, callId).putExtra(EXTRA_MARK_PROFESSIONAL, true)
    }
}
