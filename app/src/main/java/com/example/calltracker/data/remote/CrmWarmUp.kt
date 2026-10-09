package com.example.calltracker.data.remote

import com.example.calltracker.di.ApplicationScope
import com.example.calltracker.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wakes the hosted CRM when the process starts (app opened, or a call-state broadcast at the end of a call).
 * A free Render service sleeps after ~15 min idle and needs up to a minute to wake; by the time the sweep has read
 * the call and asks the CRM about the number, it is answering. Best effort: offline or failing is fine.
 */
@Singleton
class CrmWarmUp @Inject constructor(
    private val api: CrmApi,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun wakeUp() {
        scope.launch(io) {
            try {
                api.health()
            } catch (_: IOException) {
                // Offline or still waking: the lookup and upload workers retry on their own.
            }
        }
    }
}
