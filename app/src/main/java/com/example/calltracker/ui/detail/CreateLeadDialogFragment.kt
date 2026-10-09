package com.example.calltracker.ui.detail

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.example.calltracker.R
import com.example.calltracker.databinding.DialogCreateLeadBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Asks for the lead's name and company; the call's number is used as the phone. Survives rotation. */
class CreateLeadDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogCreateLeadBinding.inflate(layoutInflater)
        if (savedInstanceState == null) binding.name.setText(arguments?.getString(ARG_SUGGESTED_NAME))

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lead_title)
            .setView(binding.root)
            .setPositiveButton(R.string.lead_create, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Validate before closing: an empty name keeps the dialog open with an error.
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val name = binding.name.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    binding.nameLayout.error = getString(R.string.lead_name_required)
                    return@setOnClickListener
                }
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_NAME to name, RESULT_COMPANY to binding.company.text?.toString()?.trim()),
                )
                dismiss()
            }
        }
        return dialog
    }

    companion object {
        const val TAG = "create_lead"
        const val REQUEST_KEY = "create_lead_result"
        const val RESULT_NAME = "name"
        const val RESULT_COMPANY = "company"
        private const val ARG_SUGGESTED_NAME = "suggested_name"

        fun newInstance(suggestedName: String?) = CreateLeadDialogFragment().apply {
            arguments = bundleOf(ARG_SUGGESTED_NAME to suggestedName)
        }
    }
}
