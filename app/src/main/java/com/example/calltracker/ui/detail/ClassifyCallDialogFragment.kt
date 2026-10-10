package com.example.calltracker.ui.detail

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.example.calltracker.databinding.DialogClassifyCallBinding
import com.example.calltracker.domain.model.Classification
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Is this a business call?" with a big Professional / Personal choice. Returns the answer through the Fragment
 * Result API, so it survives rotation; the choice made so far is kept in the saved state.
 */
class ClassifyCallDialogFragment : DialogFragment() {

    private var choice: Classification? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogClassifyCallBinding.inflate(layoutInflater)
        choice = savedInstanceState?.getString(STATE_CHOICE)?.let(Classification::valueOf)
        binding.callLine.text = requireArguments().getString(ARG_CALL_LINE)

        fun render() {
            binding.professionalOption.isChecked = choice == Classification.BUSINESS
            binding.personalOption.isChecked = choice == Classification.PERSONAL
            binding.continueButton.isEnabled = choice != null
        }
        binding.professionalOption.setOnClickListener {
            choice = Classification.BUSINESS
            render()
        }
        binding.personalOption.setOnClickListener {
            choice = Classification.PERSONAL
            render()
        }
        binding.closeButton.setOnClickListener { dismiss() }
        binding.continueButton.setOnClickListener {
            val chosen = choice ?: return@setOnClickListener
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_CLASSIFICATION to chosen.name, RESULT_APPLY_TO_NUMBER to binding.applyToNumber.isChecked),
            )
            dismiss()
        }
        render()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CHOICE, choice?.name)
    }

    companion object {
        const val TAG = "classify_call"
        const val REQUEST_KEY = "classify_call_result"
        const val RESULT_CLASSIFICATION = "classification"
        const val RESULT_APPLY_TO_NUMBER = "apply_to_number"
        private const val ARG_CALL_LINE = "call_line"
        private const val STATE_CHOICE = "choice"

        /** [callLine] says which call this is about, e.g. "+91 98765 43210 · Missed · 11:36". */
        fun newInstance(callLine: String) = ClassifyCallDialogFragment().apply {
            arguments = bundleOf(ARG_CALL_LINE to callLine)
        }
    }
}
