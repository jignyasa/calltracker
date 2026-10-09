package com.example.calltracker.ui.rules

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.calltracker.R
import com.example.calltracker.databinding.DialogAddRuleBinding
import com.example.calltracker.databinding.FragmentRulesBinding
import com.example.calltracker.databinding.ItemRuleBinding
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.NumberRule
import com.example.calltracker.ui.common.Formatters
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RulesFragment : Fragment(R.layout.fragment_rules) {

    private val viewModel: RulesViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentRulesBinding.bind(view)
        requireActivity().setTitle(R.string.title_rules)
        val adapter = RuleAdapter(onMenu = ::showRuleMenu)
        binding.list.adapter = adapter
        binding.addButton.setOnClickListener { AddRuleDialogFragment().show(childFragmentManager, AddRuleDialogFragment.TAG) }
        childFragmentManager.setFragmentResultListener(AddRuleDialogFragment.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val classification = Classification.valueOf(result.getString(AddRuleDialogFragment.RESULT_TYPE)!!)
            viewModel.addRule(result.getString(AddRuleDialogFragment.RESULT_NUMBER).orEmpty(), classification)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        adapter.submitList(state.rules)
                        binding.empty.isVisible = !state.loading && state.rules.isEmpty()
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        val message = when (event) {
                            is RulesEvent.Saved -> resources.getQuantityString(R.plurals.rules_saved, event.updatedCalls, event.updatedCalls)
                            is RulesEvent.Deleted -> resources.getQuantityString(R.plurals.rules_deleted, event.updatedCalls, event.updatedCalls)
                            RulesEvent.InvalidNumber -> getString(R.string.rules_invalid_number)
                        }
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showRuleMenu(anchor: View, rule: NumberRule) {
        PopupMenu(requireContext(), anchor).apply {
            val switchLabel = if (rule.classification == Classification.BUSINESS) {
                R.string.rules_switch_to_personal
            } else {
                R.string.rules_switch_to_business
            }
            menu.add(0, MENU_SWITCH, 0, switchLabel)
            menu.add(0, MENU_DELETE, 1, R.string.rules_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_SWITCH -> viewModel.switch(rule)
                    MENU_DELETE -> viewModel.delete(rule)
                }
                true
            }
        }.show()
    }

    private class RuleAdapter(
        private val onMenu: (View, NumberRule) -> Unit,
    ) : ListAdapter<NumberRule, RuleAdapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(rule: NumberRule) {
                val context = binding.root.context
                binding.number.text = rule.normalizedNumber
                val kind = context.getString(
                    if (rule.classification == Classification.BUSINESS) R.string.rules_always_business else R.string.rules_always_personal,
                )
                val since = context.getString(R.string.rules_since, Formatters.date(context, rule.createdAt))
                binding.rule.text = context.getString(R.string.call_subtitle_short, kind, since)
                binding.menuButton.setOnClickListener { onMenu(it, rule) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<NumberRule>() {
            override fun areItemsTheSame(old: NumberRule, new: NumberRule) = old.normalizedNumber == new.normalizedNumber
            override fun areContentsTheSame(old: NumberRule, new: NumberRule) = old == new
        }
    }

    private companion object {
        const val MENU_SWITCH = 1
        const val MENU_DELETE = 2
    }
}

/** Number + business/personal. The number is normalised (and validated) by the use case. */
class AddRuleDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogAddRuleBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_add)
            .setView(binding.root)
            .setPositiveButton(R.string.rules_add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val number = binding.number.text?.toString().orEmpty().trim()
                if (number.isEmpty()) {
                    binding.numberLayout.error = getString(R.string.rules_invalid_number)
                    return@setOnClickListener
                }
                val type = if (binding.type.checkedRadioButtonId == R.id.typePersonal) Classification.PERSONAL else Classification.BUSINESS
                setFragmentResult(REQUEST_KEY, bundleOf(RESULT_NUMBER to number, RESULT_TYPE to type.name))
                dismiss()
            }
        }
        return dialog
    }

    companion object {
        const val TAG = "add_rule"
        const val REQUEST_KEY = "add_rule_result"
        const val RESULT_NUMBER = "number"
        const val RESULT_TYPE = "type"
    }
}
