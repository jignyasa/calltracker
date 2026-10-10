package com.example.calltracker.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentSettingsBinding
import com.example.calltracker.ui.common.BackgroundRestrictions
import com.example.calltracker.ui.navigator
import com.example.calltracker.ui.postcall.PostCallLauncher
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private val viewModel: SettingsViewModel by viewModels()
    private var binding: FragmentSettingsBinding? = null
    private var rendering = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view).also { binding = it }
        requireActivity().setTitle(R.string.title_settings)

        binding.simGroup.setOnCheckedChangeListener { group, checkedId ->
            if (rendering || checkedId == View.NO_ID) return@setOnCheckedChangeListener
            viewModel.selectWorkSim(group.findViewById<RadioButton>(checkedId).tag as Int?)
        }
        binding.notificationButton.setOnClickListener {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName),
            )
        }
        binding.popupButton.setOnClickListener { open(PostCallLauncher.overlaySettingsIntent(requireContext())) }
        binding.batteryButton.setOnClickListener { BackgroundRestrictions.requestIgnoreBatteryOptimizations(requireContext()) }
        binding.rulesRow.setOnClickListener { navigator.openRules() }
        binding.autoLeadSwitch.setOnCheckedChangeListener { _, checked ->
            if (!rendering) viewModel.setAutoCreateLeads(checked)
        }
        binding.aboutRow.setOnClickListener { navigator.openAbout() }
        BackgroundRestrictions.oemGuide?.let { guide ->
            val brand = getString(guide.brand)
            binding.oemSection.isVisible = true
            binding.oemTitle.text = getString(R.string.settings_oem_title, brand)
            binding.oemSteps.setText(guide.steps)
            binding.oemButton.text = getString(R.string.settings_oem_open, brand)
            binding.oemButton.setOnClickListener { BackgroundRestrictions.openOemSettings(requireContext(), guide) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(binding, it) } }
                launch {
                    viewModel.events.collect { event ->
                        if (event is SettingsEvent.WorkSimUpdated) {
                            Snackbar.make(binding.root, resources.getQuantityString(R.plurals.settings_work_sim_updated, event.updatedCalls, event.updatedCalls), Snackbar.LENGTH_LONG)
                                .show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just changed these in system settings.
        viewModel.refreshSims()
        val binding = binding ?: return
        val context = requireContext()
        val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        binding.notificationStatus.setText(if (notificationsOn) R.string.settings_notifications_on else R.string.settings_notifications_off)
        val canPopUp = PostCallLauncher.canPopUp(context)
        binding.popupStatus.setText(if (canPopUp) R.string.settings_popup_on else R.string.settings_popup_off)
        binding.popupButton.isVisible = !canPopUp
        val unrestricted = BackgroundRestrictions.isIgnoringBatteryOptimizations(context)
        binding.batteryStatus.setText(if (unrestricted) R.string.settings_battery_ok else R.string.settings_battery_restricted)
        binding.batteryButton.isVisible = !unrestricted
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentSettingsBinding, state: SettingsUiState) {
        rendering = true
        try {
            val options = listOf<Pair<Int?, String>>(null to getString(R.string.settings_work_sim_none)) +
                state.sims.map { sim ->
                    sim.slot to if (sim.label.isBlank()) {
                        getString(R.string.settings_sim_label_unnamed, sim.slot + 1)
                    } else {
                        getString(R.string.settings_sim_label, sim.slot + 1, sim.label)
                    }
                }
            // Rebuild only when the SIM list changed, so a tap isn't lost to a re-render.
            val tags = (0 until binding.simGroup.childCount).map { binding.simGroup.getChildAt(it).tag as Int? }
            if (tags != options.map { it.first }) {
                binding.simGroup.removeAllViews()
                options.forEach { (slot, label) ->
                    binding.simGroup.addView(RadioButton(requireContext()).apply {
                        id = View.generateViewId()
                        tag = slot
                        text = label
                    })
                }
            }
            (0 until binding.simGroup.childCount)
                .map { binding.simGroup.getChildAt(it) as RadioButton }
                .forEach { button ->
                    button.isChecked = button.tag == state.workSimSlot
                    button.isEnabled = !state.updating
                }
            binding.noSims.isVisible = !state.loading && state.sims.isEmpty()
            binding.autoLeadSwitch.isChecked = state.autoCreateLeads
        } finally {
            rendering = false
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)),
            )
        }
    }
}
