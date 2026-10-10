package com.example.calltracker.ui.common

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import com.example.calltracker.R

/**
 * A dialog that fills the screen like a page (Create lead, Call saved). It draws edge to edge on every Android
 * version and pads itself: [topInsetView] gets the status bar inset, [bottomInsetView] the navigation bar and
 * keyboard insets.
 */
abstract class FullScreenDialogFragment(layoutRes: Int) : DialogFragment(layoutRes) {

    /** Light status bar icons over a blue bar; dark ones over a light page. */
    protected open val lightStatusBarIcons: Boolean = true

    override fun getTheme(): Int = R.style.Theme_CallTracker_FullScreenDialog

    protected abstract fun topInsetView(view: View): View
    protected abstract fun bottomInsetView(view: View): View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val top = topInsetView(view)
        val bottom = bottomInsetView(view)
        val topStart = top.paddingTop
        val bottomStart = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            top.updatePadding(top = topStart + bars.top)
            bottom.updatePadding(bottom = bottomStart + bars.bottom)
            view.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") // Ignored on Android 15+, where windows are always edge to edge.
        window.statusBarColor = Color.TRANSPARENT
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            !lightStatusBarIcons && !night
    }
}
