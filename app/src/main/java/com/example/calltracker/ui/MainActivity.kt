package com.example.calltracker.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.example.calltracker.R
import com.example.calltracker.databinding.ActivityMainBinding
import com.example.calltracker.ui.detail.CallDetailFragment
import com.example.calltracker.ui.rules.RulesFragment
import com.example.calltracker.ui.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), AppNavigator {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportFragmentManager.addOnBackStackChangedListener { updateUpButton() }
        updateUpButton()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun openCallDetail(callId: Long) = push(CallDetailFragment.newInstance(callId))

    override fun openRules() = push(RulesFragment())

    override fun openSettings() = push(SettingsFragment())

    private fun push(fragment: Fragment) {
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            setCustomAnimations(R.anim.slide_in, R.anim.fade_out, R.anim.fade_in, R.anim.slide_out)
            replace(R.id.content, fragment)
            addToBackStack(null)
        }
    }

    private fun updateUpButton() {
        val nested = supportFragmentManager.backStackEntryCount > 0
        supportActionBar?.setDisplayHomeAsUpEnabled(nested)
        if (!nested) setTitle(R.string.app_name)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != ACTION_LOG_CALL) return
        val callId = intent.getLongExtra(EXTRA_CALL_ID, -1)
        if (callId >= 0) {
            viewModel.onLogCallRequested(callId)
            // Back from the form goes to the list, not to a second copy of it.
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            openCallDetail(callId)
        }
        // Consumed: a rotation or a return from recents must not re-run it.
        intent.action = Intent.ACTION_MAIN
    }

    companion object {
        const val ACTION_LOG_CALL = "com.example.calltracker.action.LOG_CALL"
        const val EXTRA_CALL_ID = "call_id"
    }
}
