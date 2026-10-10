package com.example.calltracker.ui.queue

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.content.getSystemService
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentSyncQueueBinding
import com.example.calltracker.ui.calls.CallAdapter
import com.example.calltracker.ui.calls.SyncFilter
import com.example.calltracker.ui.navigator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Sync Queue: what is waiting, failed or done uploading, plus an offline banner while there's no network. */
@AndroidEntryPoint
class SyncQueueFragment : Fragment(R.layout.fragment_sync_queue) {

    private val viewModel: SyncQueueViewModel by viewModels()
    private var binding: FragmentSyncQueueBinding? = null
    private val adapter = CallAdapter(onClick = { navigator.openCallDetail(it.call.id) })
    private val tabOrder = listOf(SyncFilter.PENDING, SyncFilter.FAILED, SyncFilter.SYNCED)
    private var rendering = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateOffline()
        override fun onLost(network: Network) = updateOffline()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateOffline()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSyncQueueBinding.bind(view).also { binding = it }
        requireActivity().setTitle(R.string.title_sync_queue)
        binding.list.adapter = adapter
        tabOrder.forEach { binding.tabs.addTab(binding.tabs.newTab()) }
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!rendering) viewModel.selectTab(tabOrder[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        addMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(binding, it) } }
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
        requireContext().getSystemService<ConnectivityManager>()?.registerDefaultNetworkCallback(networkCallback)
        updateOffline()
    }

    override fun onStop() {
        requireContext().getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentSyncQueueBinding, state: SyncQueueUiState) {
        rendering = true
        try {
            binding.tabs.getTabAt(0)?.text = getString(R.string.queue_tab_pending, state.pending)
            binding.tabs.getTabAt(1)?.text = getString(R.string.queue_tab_failed, state.failed)
            binding.tabs.getTabAt(2)?.text = getString(R.string.filter_sync_synced)
            val index = tabOrder.indexOf(state.tab)
            if (binding.tabs.selectedTabPosition != index) binding.tabs.getTabAt(index)?.select()
            binding.empty.setText(
                when (state.tab) {
                    SyncFilter.PENDING -> R.string.queue_empty_pending
                    SyncFilter.FAILED -> R.string.queue_empty_failed
                    SyncFilter.SYNCED -> R.string.queue_empty_synced
                },
            )
        } finally {
            rendering = false
        }
    }

    /** Called from the network callback's thread too, so it hops to the view before touching it. */
    private fun updateOffline() {
        val view = binding?.root ?: return
        view.post {
            val binding = binding ?: return@post
            val manager = context?.getSystemService<ConnectivityManager>() ?: return@post
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            binding.offlineBanner.isVisible = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true
        }
    }

    private fun addMenu() {
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, inflater: MenuInflater) = inflater.inflate(R.menu.menu_sync_queue, menu)

                override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                    R.id.action_upload_now -> true.also {
                        viewModel.uploadNow()
                        binding?.let { Snackbar.make(it.root, R.string.upload_started, Snackbar.LENGTH_SHORT).show() }
                    }
                    else -> false
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED,
        )
    }
}
