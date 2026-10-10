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
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentCallListBinding
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.ui.common.BackgroundRestrictions
import com.example.calltracker.ui.navigator
import com.example.calltracker.ui.permissions.CallPermissions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallListFragment : Fragment(R.layout.fragment_call_list) {

    private val viewModel: CallListViewModel by viewModels()
    private var binding: FragmentCallListBinding? = null
    private val adapter = CallAdapter(onClick = { navigator.openCallDetail(it.call.id) })

    /** Set when the system won't show the dialog any more ("Don't ask again"); the button then opens app settings. */
    private var sendToSettings = false

    private var batteryRestricted = false

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
        requireActivity().setTitle(R.string.title_call_activities)
        binding.list.adapter = adapter
        binding.grantButton.setOnClickListener { requestPermissions() }
        binding.batteryFix.setOnClickListener { navigator.openSettings() }
        binding.batteryDismiss.setOnClickListener { viewModel.dismissBatteryHint() }
        binding.uploadSummary.setOnClickListener { navigator.openSyncQueue() }
        // No toolbar actions here: upload status and Upload now live on the Sync queue screen (the banner opens it).
        bindFilters(binding)
        keepNewestCallsInView(binding)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { state -> render(binding, state) } }
                launch { viewModel.calls.collectLatest(adapter::submitData) }
                launch {
                    adapter.loadStateFlow.collect { loads ->
                        binding.empty.isVisible = loads.refresh is LoadState.NotLoading && adapter.itemCount == 0
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updatePermissionCard()
        // The user may come back from the battery settings: re-check every time.
        batteryRestricted = !BackgroundRestrictions.isIgnoringBatteryOptimizations(requireContext())
        binding?.let { updateBatteryCard(it, viewModel.state.value) }
        if (CallPermissions.hasRequired(requireContext())) viewModel.refresh()
    }

    /** New calls arrive at the top; if the user is looking at the top, keep them there instead of above the fold. */
    private fun keepNewestCallsInView(binding: FragmentCallListBinding) {
        var atTop = true
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                atTop = (recyclerView.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition() <= 0
            }
        })
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0 && atTop) binding.list.scrollToPosition(0)
            }
        })
    }

    private fun updateBatteryCard(binding: FragmentCallListBinding, state: CallListUiState) {
        binding.batteryCard.isVisible = batteryRestricted && !state.batteryHintDismissed
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentCallListBinding, state: CallListUiState) {
        updateBatteryCard(binding, state)
        val summary = listOfNotNull(
            state.waitingToUpload.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.upload_waiting, it, it) },
            state.uploadFailed.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.upload_failed, it, it) },
        ).joinToString(" · ")
        binding.uploadSummary.text = summary
        binding.uploadSummary.isVisible = summary.isNotEmpty()

        binding.empty.setText(if (state.filter.isActive) R.string.empty_calls_filtered else R.string.empty_calls)
        val tab = when (state.filter.classification) {
            null -> R.id.tabAll
            Classification.BUSINESS -> R.id.tabBusiness
            Classification.PERSONAL -> R.id.tabPersonal
            Classification.UNCLASSIFIED -> R.id.tabUnclassified
        }
        if (binding.classificationTabs.checkedButtonId != tab) binding.classificationTabs.check(tab)
    }

    private fun bindFilters(binding: FragmentCallListBinding) {
        binding.classificationTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setClassificationFilter(
                when (checkedId) {
                    R.id.tabBusiness -> Classification.BUSINESS
                    R.id.tabPersonal -> Classification.PERSONAL
                    R.id.tabUnclassified -> Classification.UNCLASSIFIED
                    else -> null
                },
            )
        }
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
