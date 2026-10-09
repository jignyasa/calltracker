package com.example.calltracker.ui.calls

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
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
        binding.list.adapter = adapter
        binding.grantButton.setOnClickListener { requestPermissions() }
        binding.batteryFix.setOnClickListener { navigator.openSettings() }
        binding.batteryDismiss.setOnClickListener { viewModel.dismissBatteryHint() }
        bindFilters(binding)
        keepNewestCallsInView(binding)
        addMenu()

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
        binding.classificationChips.check(
            when (state.filter.classification) {
                null -> R.id.chipAll
                Classification.BUSINESS -> R.id.chipBusiness
                Classification.PERSONAL -> R.id.chipPersonal
                Classification.UNCLASSIFIED -> R.id.chipUnclassified
            },
        )
        if (state.filter.sync != shownSyncFilter) {
            shownSyncFilter = state.filter.sync
            requireActivity().invalidateMenu() // re-tint the filter icon
        }
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
    }

    /** The status filter lives behind the toolbar's filter icon; the icon takes the accent colour while it's on. */
    private var shownSyncFilter: SyncFilter? = null

    private fun showStatusFilterMenu() {
        // The menu item's own view, so the pop-up opens under the icon (left of the ⋮).
        val anchor = requireActivity().findViewById<View>(R.id.action_status_filter)
            ?: requireActivity().findViewById(R.id.toolbar) // very narrow screen: the icon went into the overflow
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_status_filter, menu)
            menu.findItem(
                when (viewModel.state.value.filter.sync) {
                    null -> R.id.status_any
                    SyncFilter.PENDING -> R.id.status_pending
                    SyncFilter.SYNCED -> R.id.status_synced
                    SyncFilter.FAILED -> R.id.status_failed
                },
            ).isChecked = true
            setOnMenuItemClickListener { item ->
                viewModel.setSyncFilter(
                    when (item.itemId) {
                        R.id.status_pending -> SyncFilter.PENDING
                        R.id.status_synced -> SyncFilter.SYNCED
                        R.id.status_failed -> SyncFilter.FAILED
                        else -> null
                    },
                )
                true
            }
        }.show()
    }

    private fun addMenu() {
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_call_list, menu)

                override fun onPrepareMenu(menu: Menu) {
                    val active = shownSyncFilter != null
                    val tint = MaterialColors.getColor(
                        requireView(),
                        if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                    menu.findItem(R.id.action_status_filter)?.let { item ->
                        MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(tint))
                    }
                }

                override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                    R.id.action_status_filter -> true.also { showStatusFilterMenu() }
                    R.id.action_upload_now -> true.also {
                        viewModel.uploadNow()
                        binding?.let { Snackbar.make(it.root, R.string.upload_started, Snackbar.LENGTH_SHORT).show() }
                    }
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
