package com.example.calltracker.ui.detail

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResult
import com.example.calltracker.R
import com.example.calltracker.databinding.DialogCreateLeadBinding
import com.example.calltracker.ui.common.FullScreenDialogFragment

/**
 * Create New Lead, as a full-screen form. Asks for the lead's name and company; the call's number is the phone and
 * the source is the call (what the CRM's POST /leads accepts). Survives rotation; returns through the Fragment
 * Result API.
 */
class CreateLeadDialogFragment : FullScreenDialogFragment(R.layout.dialog_create_lead) {

    override fun topInsetView(view: View): View = DialogCreateLeadBinding.bind(view).appBar
    override fun bottomInsetView(view: View): View = DialogCreateLeadBinding.bind(view).buttons

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = DialogCreateLeadBinding.bind(view)
        if (savedInstanceState == null) binding.name.setText(arguments?.getString(ARG_SUGGESTED_NAME))
        binding.phone.setText(arguments?.getString(ARG_PHONE))
        binding.name.doAfterTextChanged { binding.nameLayout.error = null }
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.cancelButton.setOnClickListener { dismiss() }
        // Validate before closing: an empty name keeps the form open with an error.
        binding.saveButton.setOnClickListener {
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

    companion object {
        const val TAG = "create_lead"
        const val REQUEST_KEY = "create_lead_result"
        const val RESULT_NAME = "name"
        const val RESULT_COMPANY = "company"
        private const val ARG_SUGGESTED_NAME = "suggested_name"
        private const val ARG_PHONE = "phone"

        fun newInstance(suggestedName: String?, phone: String?) = CreateLeadDialogFragment().apply {
            arguments = bundleOf(ARG_SUGGESTED_NAME to suggestedName, ARG_PHONE to phone)
        }
    }
}
