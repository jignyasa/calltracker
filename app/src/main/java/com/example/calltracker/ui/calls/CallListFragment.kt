package com.example.calltracker.ui.calls

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentCallListBinding
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.ui.navigator
import com.example.calltracker.ui.permissions.CallPermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallListFragment : Fragment(R.layout.fragment_call_list) {

    private val viewModel: CallListViewModel by viewModels()
    private var binding: FragmentCallListBinding? = null
    private val adapter = CallAdapter(onClick = { navigator.openCallDetail(it.call.id) })

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
        bindFilters(binding)
        addMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(binding, state) }
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

    private fun render(binding: FragmentCallListBinding, state: CallListUiState) {
        // New calls arrive at the top; if the user is looking at the top, keep them there instead of above the fold.
        val layoutManager = binding.list.layoutManager as LinearLayoutManager
        val atTop = layoutManager.findFirstCompletelyVisibleItemPosition() <= 0
        adapter.submitList(state.calls) { if (atTop) binding.list.scrollToPosition(0) }

        binding.empty.isVisible = !state.loading && state.calls.isEmpty()
        binding.empty.setText(if (state.filter.isActive) R.string.empty_calls_filtered else R.string.empty_calls)
        binding.classificationChips.check(
            when (state.filter.classification) {
                null -> R.id.chipAll
                Classification.BUSINESS -> R.id.chipBusiness
                Classification.PERSONAL -> R.id.chipPersonal
                Classification.UNCLASSIFIED -> R.id.chipUnclassified
            },
        )
        binding.syncChips.check(
            when (state.filter.sync) {
                null -> R.id.chipSyncAny
                SyncFilter.PENDING -> R.id.chipSyncPending
                SyncFilter.SYNCED -> R.id.chipSyncSynced
                SyncFilter.FAILED -> R.id.chipSyncFailed
            },
        )
    }

    private fun bindFilters(binding: FragmentCallListBinding) {
        binding.classificationChips.setOnCheckedStateChangeListener { _, checked ->
            viewModel.setClassificationFilter(
                when (checked.firstOrNull()) {
                    R.id.chipBusiness -> Classification.BUSINESS
                    R.id.chipPersonal -> Classification.PERSONAL
                    R.id.chipUnclassified -> Classification.UNCLASSIFIED
                    else -> null
                },
            )
        }
        binding.syncChips.setOnCheckedStateChangeListener { _, checked ->
            viewModel.setSyncFilter(
                when (checked.firstOrNull()) {
                    R.id.chipSyncPending -> SyncFilter.PENDING
                    R.id.chipSyncSynced -> SyncFilter.SYNCED
                    R.id.chipSyncFailed -> SyncFilter.FAILED
                    else -> null
                },
            )
        }
    }

    private fun addMenu() {
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_call_list, menu)

                override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                    R.id.action_rules -> true.also { navigator.openRules() }
                    R.id.action_settings -> true.also { navigator.openSettings() }
                    else -> false
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
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
