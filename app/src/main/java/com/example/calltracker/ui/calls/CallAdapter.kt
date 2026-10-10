package com.example.calltracker.ui.calls

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.calltracker.databinding.ItemCallBinding
import com.example.calltracker.domain.model.CallListEntry
import com.example.calltracker.ui.common.CallVisuals
import com.example.calltracker.ui.common.Formatters

/** Call rows for the call list and the sync queue. */
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
            binding.row.setOnClickListener { onClick(entry) }
            val name = Formatters.displayName(context, call)
            binding.title.text = name
            CallVisuals.bindAvatar(binding.avatar, name)
            binding.who.text = CallVisuals.whoLine(context, call)
            CallVisuals.applyPill(binding.typePill, CallVisuals.callTypePill(call.type))
            binding.time.text = Formatters.dayTime(context, call.startedAt)
            binding.duration.text = Formatters.duration(context, call.durationSec)
            CallVisuals.applyPill(binding.classification, CallVisuals.classificationPill(call.classification), withIcon = false)
            CallVisuals.applySyncIcon(binding.syncIcon, entry.syncStatus)
        }
    }

    private object Diff : DiffUtil.ItemCallback<CallListEntry>() {
        override fun areItemsTheSame(old: CallListEntry, new: CallListEntry) = old.call.id == new.call.id
        override fun areContentsTheSame(old: CallListEntry, new: CallListEntry) = old == new
    }
}
