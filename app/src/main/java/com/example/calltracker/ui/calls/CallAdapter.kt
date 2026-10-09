package com.example.calltracker.ui.calls

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.calltracker.R
import com.example.calltracker.databinding.ItemCallBinding
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.ui.common.Formatters

class CallAdapter(
    private val onClick: (CallRecord) -> Unit,
) : ListAdapter<CallRecord, CallAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(call: CallRecord) {
            val context = binding.root.context
            binding.root.setOnClickListener { onClick(call) }
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
            binding.classification.setText(
                when (call.classification) {
                    Classification.BUSINESS -> R.string.classification_business
                    Classification.PERSONAL -> R.string.classification_personal
                    Classification.UNCLASSIFIED -> R.string.classification_unclassified
                },
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<CallRecord>() {
        override fun areItemsTheSame(old: CallRecord, new: CallRecord) = old.id == new.id
        override fun areContentsTheSame(old: CallRecord, new: CallRecord) = old == new
    }
}
