package com.example.calltracker.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.example.calltracker.R
import com.example.calltracker.databinding.ViewAvatarBinding
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus
import kotlin.math.abs

/** The shared visual vocabulary of the call screens: avatars, coloured pills and the "Lead · Company" line. */
object CallVisuals {

    private val avatarColors = intArrayOf(
        R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4, R.color.avatar_5, R.color.avatar_6,
    )

    /** A pill's look: background, text/icon colour and an optional leading icon. */
    data class Pill(@StringRes val label: Int, @ColorRes val background: Int, @ColorRes val foreground: Int, @DrawableRes val icon: Int? = null)

    /**
     * Initials for a person's name ("Rahul Patel" → "RP"); null for numbers and private calls, which get a person
     * icon instead.
     */
    fun initials(name: String): String? {
        val words = name.split(' ', '-', '.').filter { it.isNotBlank() && it.first().isLetter() }
        if (words.isEmpty()) return null
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    /** [sizeDp] is the avatar's size; the initials scale with it. */
    fun bindAvatar(avatar: ViewAvatarBinding, name: String, sizeDp: Int = 40) {
        val context = avatar.root.context
        val initials = initials(name)
        avatar.avatarText.isVisible = initials != null
        avatar.avatarIcon.isVisible = initials == null
        avatar.avatarText.text = initials
        avatar.avatarText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.38f)
        val color = if (initials == null) R.color.avatar_unknown else avatarColors[abs(name.hashCode() % avatarColors.size)]
        avatar.root.backgroundTintList = ContextCompat.getColorStateList(context, color)
    }

    fun classificationPill(classification: Classification): Pill = when (classification) {
        Classification.BUSINESS -> Pill(R.string.classification_business, R.color.pill_professional_bg, R.color.pill_professional_fg)
        Classification.PERSONAL -> Pill(R.string.classification_personal, R.color.pill_personal_bg, R.color.pill_personal_fg)
        Classification.UNCLASSIFIED -> Pill(R.string.classification_unclassified, R.color.pill_unsorted_bg, R.color.pill_unsorted_fg)
    }

    fun callTypePill(type: CallType): Pill = when (type) {
        CallType.INCOMING -> Pill(Formatters.callTypeLabel(type), R.color.pill_ok_bg, R.color.pill_ok_fg, R.drawable.ic_call_incoming)
        CallType.OUTGOING -> Pill(Formatters.callTypeLabel(type), R.color.pill_ok_bg, R.color.pill_ok_fg, R.drawable.ic_call_outgoing)
        CallType.MISSED, CallType.REJECTED, CallType.VOICEMAIL ->
            Pill(Formatters.callTypeLabel(type), R.color.pill_bad_bg, R.color.pill_bad_fg, R.drawable.ic_call_missed)
        CallType.BLOCKED -> Pill(Formatters.callTypeLabel(type), R.color.pill_bad_bg, R.color.pill_bad_fg, R.drawable.ic_block)
        CallType.OTHER -> Pill(Formatters.callTypeLabel(type), R.color.pill_neutral_bg, R.color.pill_neutral_fg, R.drawable.ic_phone)
    }

    /** Null when the call isn't in the upload queue (personal, unsorted, or business without an activity yet). */
    fun syncPill(status: SyncStatus?): Pill? = when (status) {
        null -> null
        SyncStatus.SYNCED -> Pill(R.string.sync_badge_synced, R.color.pill_ok_bg, R.color.pill_ok_fg, R.drawable.ic_check_circle)
        SyncStatus.FAILED_PERMANENT -> Pill(R.string.sync_badge_failed, R.color.pill_bad_bg, R.color.pill_bad_fg, R.drawable.ic_error)
        SyncStatus.PENDING, SyncStatus.IN_FLIGHT, SyncStatus.FAILED_RETRYABLE ->
            Pill(R.string.sync_badge_pending, R.color.pill_pending_bg, R.color.pill_pending_fg, R.drawable.ic_cloud_upload)
    }

    fun applyPill(view: TextView, pill: Pill, withIcon: Boolean = true) {
        val context = view.context
        view.setText(pill.label)
        view.backgroundTintList = ContextCompat.getColorStateList(context, pill.background)
        val foreground = ContextCompat.getColor(context, pill.foreground)
        view.setTextColor(foreground)
        val icon = pill.icon.takeIf { withIcon }?.let { ContextCompat.getDrawable(context, it)?.mutate() }
        icon?.setBounds(0, 0, view.lineHeight, view.lineHeight)
        view.setCompoundDrawablesRelative(icon, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(foreground))
    }

    /**
     * The small status icon at the top right of a list row (cloud = waiting, check = synced, ! = failed). Hidden
     * calls keep the space, so the rows stay aligned.
     */
    fun applySyncIcon(view: ImageView, status: SyncStatus?) {
        val pill = syncPill(status)
        view.isInvisible = pill?.icon == null
        if (pill?.icon == null) return
        view.setImageResource(pill.icon)
        view.imageTintList = ContextCompat.getColorStateList(view.context, pill.foreground)
        view.contentDescription = view.context.getString(pill.label)
    }

    /** "Lead · ABC Traders", "Contact · Global Tech", "Saved contact", or "Unknown number". */
    fun whoLine(context: Context, call: CallRecord): String {
        val crm = call.crmMatch
        return when {
            crm != null -> {
                val kind = context.getString(if (crm.type == CrmRecordType.LEAD) R.string.crm_kind_lead else R.string.crm_kind_contact)
                crm.company?.let { context.getString(R.string.detail_crm, kind, it) } ?: kind
            }
            call.deviceContactName != null -> context.getString(R.string.source_device_contact)
            else -> context.getString(R.string.who_unknown)
        }
    }
}
