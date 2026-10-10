package com.example.calltracker.ui.about

import android.os.Bundle
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.calltracker.R
import com.example.calltracker.databinding.FragmentAboutBinding
import com.example.calltracker.databinding.ItemFeatureBinding

/** "Why this approach?": the design choices behind the app, in plain words. Static content. */
class AboutFragment : Fragment(R.layout.fragment_about) {

    private data class Feature(
        @DrawableRes val icon: Int,
        @ColorRes val background: Int,
        @ColorRes val tint: Int,
        @StringRes val title: Int,
        @StringRes val text: Int,
    )

    private val features = listOf(
        Feature(R.drawable.ic_phone, R.color.feature_blue_bg, R.color.pill_professional_fg, R.string.about_detection_title, R.string.about_detection_text),
        Feature(R.drawable.ic_shield, R.color.feature_green_bg, R.color.pill_ok_fg, R.string.about_privacy_title, R.string.about_privacy_text),
        Feature(R.drawable.ic_people, R.color.feature_purple_bg, R.color.feature_purple_fg, R.string.about_matching_title, R.string.about_matching_text),
        Feature(R.drawable.ic_cloud_upload, R.color.feature_orange_bg, R.color.feature_orange_fg, R.string.about_sync_title, R.string.about_sync_text),
        Feature(R.drawable.ic_smartphone, R.color.feature_teal_bg, R.color.feature_teal_fg, R.string.about_real_world_title, R.string.about_real_world_text),
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().setTitle(R.string.title_about)
        val binding = FragmentAboutBinding.bind(view)
        val context = requireContext()
        features.forEach { feature ->
            val row = ItemFeatureBinding.inflate(layoutInflater, binding.features, true)
            row.icon.setImageResource(feature.icon)
            row.icon.backgroundTintList = ContextCompat.getColorStateList(context, feature.background)
            row.icon.imageTintList = ContextCompat.getColorStateList(context, feature.tint)
            row.title.setText(feature.title)
            row.text.setText(feature.text)
        }
    }
}
