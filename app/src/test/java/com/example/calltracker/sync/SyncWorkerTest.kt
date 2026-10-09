package com.example.calltracker.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.model.UploadItemResult
import com.example.calltracker.domain.model.UploadItemStatus
import com.example.calltracker.domain.model.UploadResult
import com.example.calltracker.domain.repository.ActivityUploader
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.TestUseCases
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SyncWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)

    private fun worker(uploader: ActivityUploader) = TestListenableWorkerBuilder<SyncWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, params, useCases.upload(uploader))
        })
        .build()

    private fun answering(status: UploadItemStatus?) = object : ActivityUploader {
        override suspend fun upload(items: List<ActivityPayload>): UploadResult =
            if (status == null) {
                UploadResult.Failed("CRM error HTTP 503")
            } else {
                UploadResult.Processed(items.map { UploadItemResult(it.clientId, status, "act_1", 1) })
            }
    }

    private suspend fun queueBusinessCall() {
        repos.addCall(1, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        useCases.syncGate.reconcile(1)
    }

    @Test
    fun `everything uploaded - success`() = runTest {
        queueBusinessCall()
        assertEquals(ListenableWorker.Result.success(), worker(answering(UploadItemStatus.CREATED)).doWork())
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `CRM down - retry with backoff`() = runTest {
        queueBusinessCall()
        assertEquals(ListenableWorker.Result.retry(), worker(answering(null)).doWork())
    }

    @Test
    fun `transient item error - retry`() = runTest {
        queueBusinessCall()
        assertEquals(ListenableWorker.Result.retry(), worker(answering(UploadItemStatus.ERROR)).doWork())
    }

    @Test
    fun `permanent rejection - success (the user fixes it, no point retrying)`() = runTest {
        queueBusinessCall()
        assertEquals(ListenableWorker.Result.success(), worker(answering(UploadItemStatus.INVALID)).doWork())
        assertEquals(SyncStatus.FAILED_PERMANENT, repos.outbox.getValue(1).status)
    }

    @Test
    fun `nothing queued - success without calling the CRM`() = runTest {
        var calls = 0
        val uploader = object : ActivityUploader {
            override suspend fun upload(items: List<ActivityPayload>): UploadResult {
                calls++
                return UploadResult.Processed(emptyList())
            }
        }
        assertEquals(ListenableWorker.Result.success(), worker(uploader).doWork())
        assertEquals(0, calls)
    }
}
