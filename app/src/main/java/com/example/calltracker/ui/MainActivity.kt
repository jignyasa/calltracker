package com.example.calltracker.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.example.calltracker.R
import com.example.calltracker.databinding.ActivityMainBinding
import com.example.calltracker.ui.about.AboutFragment
import com.example.calltracker.ui.detail.CallDetailFragment
import com.example.calltracker.ui.queue.SyncQueueFragment
import com.example.calltracker.ui.rules.RulesFragment
import com.example.calltracker.ui.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), AppNavigator {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    /** Back closes the side menu first. */
    private val closeDrawerOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = binding.drawer.closeDrawer(GravityCompat.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status bar icons over the blue app bar, in light and dark mode.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            binding.appBar.updatePadding(top = bars.top)
            insets
        }
        setSupportActionBar(binding.toolbar)
        bindDrawer()
        onBackPressedDispatcher.addCallback(this, closeDrawerOnBack)
        supportFragmentManager.addOnBackStackChangedListener { updateUpButton() }
        updateUpButton()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The toolbar's left icon: the side menu on the call list, back everywhere else. */
    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            onBackPressedDispatcher.onBackPressed()
        } else {
            binding.drawer.openDrawer(GravityCompat.START)
        }
        return true
    }

    override fun openCallDetail(callId: Long, askClassification: Boolean) =
        push(CallDetailFragment.newInstance(callId, askClassification))

    override fun openRules() = push(RulesFragment())

    override fun openSettings() = push(SettingsFragment())

    override fun openSyncQueue() = push(SyncQueueFragment())

    override fun openAbout() = push(AboutFragment())

    override fun showCallList() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun push(fragment: Fragment) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(R.anim.slide_in, R.anim.fade_out, R.anim.fade_in, R.anim.slide_out)
            replace(R.id.content, fragment)
            addToBackStack(null)
        }
    }

    private fun bindDrawer() {
        // The header's blue runs up behind the status bar; its content starts below it.
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView.getHeaderView(0)) { header, insets ->
            header.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        binding.navView.setNavigationItemSelectedListener { item ->
            binding.drawer.closeDrawer(GravityCompat.START)
            // Every menu screen opens on top of the list, never on top of another menu screen.
            showCallList()
            when (item.itemId) {
                R.id.nav_sync_queue -> openSyncQueue()
                R.id.nav_rules -> openRules()
                R.id.nav_settings -> openSettings()
                R.id.nav_about -> openAbout()
            }
            true
        }
        binding.drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: android.view.View) {
                closeDrawerOnBack.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: android.view.View) {
                closeDrawerOnBack.isEnabled = false
            }
        })
    }

    private fun updateUpButton() {
        val nested = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(if (nested) 0 else R.drawable.ic_menu)
            setHomeActionContentDescription(if (nested) 0 else R.string.drawer_open)
        }
        // The side menu belongs to the list; inner screens only go back.
        binding.drawer.setDrawerLockMode(
            if (nested) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED,
        )
        if (!nested) {
            setTitle(R.string.title_call_activities)
            binding.navView.setCheckedItem(R.id.nav_calls)
        }
    }

    private fun handleIntent(intent: Intent) {
        val callId = intent.getLongExtra(EXTRA_CALL_ID, -1)
        when (intent.action) {
            ACTION_LOG_CALL -> if (callId >= 0) {
                viewModel.onLogCallRequested(callId)
                // Back from the form goes to the list, not to a second copy of it.
                showCallList()
                openCallDetail(callId)
            }
            // Tapping a "Work call?" notification: the call, with the classify dialog on top.
            ACTION_CLASSIFY_CALL -> if (callId >= 0) {
                showCallList()
                openCallDetail(callId, askClassification = true)
            }
            else -> return
        }
        // Consumed: a rotation or a return from recents must not re-run it.
        intent.action = Intent.ACTION_MAIN
    }

    companion object {
        const val ACTION_LOG_CALL = "com.example.calltracker.action.LOG_CALL"
        const val ACTION_CLASSIFY_CALL = "com.example.calltracker.action.CLASSIFY_CALL"
        const val EXTRA_CALL_ID = "call_id"
    }
}
