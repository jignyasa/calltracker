package com.example.calltracker.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.calltracker.domain.usecase.SweepCallLogUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class CallLogSweepWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sweep: SweepCallLogUseCase,
    private val scheduler: SweepScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            when (val result = sweep()) {
                SweepCallLogUseCase.Result.PermissionDenied -> {
                    Log.i(TAG, "Call log permission missing; nothing swept")
                    Result.success()
                }
                is SweepCallLogUseCase.Result.Completed -> {
                    Log.i(TAG, "Swept ${result.scanned} rows, imported ${result.imported.size}")
                    Result.success()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sweep failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            scheduler.armCallLogTrigger(replace = true)
        }
    }

    private companion object {
        const val TAG = "CallLogSweepWorker"
        const val MAX_ATTEMPTS = 3
    }
}

/** Fired by the CallLog content trigger; hands off to the regular sweep. */
@HiltWorker
class CallLogChangedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: SweepScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        scheduler.requestSweep()
        return Result.success()
    }
}
