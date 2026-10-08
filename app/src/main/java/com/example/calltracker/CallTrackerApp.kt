package com.example.calltracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.calltracker.sync.SweepScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager is initialised on demand with [HiltWorkerFactory] so `@HiltWorker` classes get
 * their dependencies (the default initializer is removed in the manifest).
 */
@HiltAndroidApp
class CallTrackerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sweepScheduler: SweepScheduler

    override fun onCreate() {
        super.onCreate()
        sweepScheduler.ensureScheduled()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()
}
