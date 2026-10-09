package com.example.calltracker.ui.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.calltracker.di.ApplicationScope
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.usecase.SetCallClassificationUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Business / Personal buttons of the post-call notification. Classifies just that call (no number rule). */
@AndroidEntryPoint
class PostCallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var setClassification: SetCallClassificationUseCase

    @Inject lateinit var notifier: PostCallNotifier

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CLASSIFY) return
        val callId = intent.getLongExtra(EXTRA_CALL_ID, -1)
        val classification = intent.getStringExtra(EXTRA_CLASSIFICATION)
            ?.let { name -> Classification.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != Classification.UNCLASSIFIED }
        if (callId < 0 || classification == null) return

        notifier.cancel(callId)
        val pending = goAsync()
        scope.launch {
            try {
                setClassification(callId, classification, applyToNumber = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Classifying from the notification failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CLASSIFY = "com.example.calltracker.action.CLASSIFY_CALL"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CLASSIFICATION = "classification"
        private const val TAG = "PostCallActionReceiver"
    }
}
