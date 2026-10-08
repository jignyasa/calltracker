package com.example.calltracker.ui.calls

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentCallListBinding
import com.example.calltracker.ui.permissions.CallPermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallListFragment : Fragment(R.layout.fragment_call_list) {

    private val viewModel: CallListViewModel by viewModels()
    private var binding: FragmentCallListBinding? = null
    private val adapter = CallAdapter(onClick = { /* Call detail arrives in Phase 5. */ })

    /** Set when the system won't show the dialog any more ("Don't ask again"); the button then opens app settings. */
    private var sendToSettings = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.hasRequired(requireContext())) {
            viewModel.refresh()
        } else {
            sendToSettings = CallPermissions.required.none { shouldShowRequestPermissionRationale(it) }
        }
        updatePermissionCard()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCallListBinding.bind(view).also { binding = it }
        binding.list.adapter = adapter
        binding.grantButton.setOnClickListener { requestPermissions() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    adapter.submitList(state.calls)
                    binding.empty.isVisible = !state.loading && state.calls.isEmpty()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updatePermissionCard()
        if (CallPermissions.hasRequired(requireContext())) viewModel.refresh()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun updatePermissionCard() {
        val binding = binding ?: return
        binding.permissionCard.isVisible = !CallPermissions.hasRequired(requireContext())
        binding.grantButton.setText(if (sendToSettings) R.string.permission_open_settings else R.string.permission_grant)
    }

    private fun requestPermissions() {
        if (sendToSettings) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)),
            )
        } else {
            permissionLauncher.launch(CallPermissions.required + CallPermissions.optional)
        }
    }
}
