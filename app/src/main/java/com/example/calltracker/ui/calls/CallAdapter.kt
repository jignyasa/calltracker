package com.example.calltracker.ui.calls

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.calltracker.R
import com.example.calltracker.databinding.ItemCallBinding
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.ui.common.Formatters
import com.google.android.material.color.MaterialColors

class CallAdapter(
    private val onClick: (CallListEntry) -> Unit,
) : PagingDataAdapter<CallListEntry, CallAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    // Placeholders are off, so a bound position always has its item.
    override fun onBindViewHolder(holder: Holder, position: Int) {
        getItem(position)?.let(holder::bind)
    }

    inner class Holder(private val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallListEntry) {
            val call = entry.call
            val context = binding.root.context
            binding.root.setOnClickListener { onClick(entry) }
            binding.title.text = Formatters.displayName(context, call)
            binding.typeIcon.setImageResource(
                when (call.type) {
                    CallType.INCOMING -> android.R.drawable.sym_call_incoming
                    CallType.OUTGOING -> android.R.drawable.sym_call_outgoing
                    else -> android.R.drawable.sym_call_missed
                },
            )
            binding.typeIcon.contentDescription = context.getString(Formatters.callTypeLabel(call.type))
            binding.subtitle.text = context.getString(
                R.string.call_subtitle,
                context.getString(Formatters.callTypeLabel(call.type)),
                Formatters.relativeTime(context, call.startedAt),
                Formatters.duration(context, call.durationSec),
            )
            binding.classification.setText(Formatters.classificationLabel(call.classification))

            val badge = when (entry.syncStatus) {
                null -> null
                SyncStatus.SYNCED -> R.string.sync_badge_synced
                SyncStatus.FAILED_PERMANENT -> R.string.sync_badge_failed
                else -> R.string.sync_badge_pending
            }
            binding.syncBadge.isVisible = badge != null
            if (badge != null) {
                binding.syncBadge.setText(badge)
                binding.syncBadge.setTextColor(
                    MaterialColors.getColor(
                        binding.syncBadge,
                        if (entry.syncStatus == SyncStatus.FAILED_PERMANENT) {
                            androidx.appcompat.R.attr.colorError
                        } else {
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        },
                    ),
                )
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<CallListEntry>() {
        override fun areItemsTheSame(old: CallListEntry, new: CallListEntry) = old.call.id == new.call.id
        override fun areContentsTheSame(old: CallListEntry, new: CallListEntry) = old == new
    }
}
