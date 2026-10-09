package com.example.calltracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.calltracker.domain.usecase.UploadPendingActivitiesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Uploads the outbox. Retries with WorkManager's exponential backoff while anything is left to retry. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val upload: UploadPendingActivitiesUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = upload()
        // Counts only: never numbers or notes.
        Log.i(TAG, "Uploaded ${result.uploaded}, rejected ${result.failedPermanently}, retry=${result.retryNeeded}")
        if (result.retryNeeded) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        // Stopped by the system (constraints lost, quota): IN_FLIGHT items are reset at the start of the next run.
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Sync failed (attempt $runAttemptCount): ${e.javaClass.simpleName}")
        Result.retry()
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
