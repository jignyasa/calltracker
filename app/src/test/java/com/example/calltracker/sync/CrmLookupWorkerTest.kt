package com.example.calltracker.sync

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.usecase.ClassifyCallUseCase
import com.example.calltracker.domain.usecase.GatherClassificationSignals
import com.example.calltracker.domain.usecase.ReclassifyNumberUseCase
import com.example.calltracker.domain.usecase.ResolveCrmMatchesUseCase
import com.example.calltracker.domain.usecase.SyncGate
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.ui.notifications.PostCallNotifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CrmLookupWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_790_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val notifier = PostCallNotifier(context)
    private val scheduler = CrmLookupScheduler(context)
    private val justNow = now - TimeUnit.MINUTES.toMillis(3)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannel()
    }

    private fun worker(prompt: Boolean, callIds: LongArray, attempt: Int = 0): CrmLookupWorker {
        val syncGate = SyncGate(repos, repos, repos, clock)
        val reclassify = ReclassifyNumberUseCase(
            repos, repos, repos, GatherClassificationSignals(repos, repos), ClassifyCallUseCase(), syncGate, repos,
        )
        val resolve = ResolveCrmMatchesUseCase(repos, repos, reclassify, clock)
        return TestListenableWorkerBuilder<CrmLookupWorker>(context)
            .setInputData(workDataOf(CrmLookupWorker.KEY_PROMPT to prompt, CrmLookupWorker.KEY_CALL_IDS to callIds))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    CrmLookupWorker(appContext, params, resolve, repos, notifier, scheduler, clock)
            })
            .build()
    }

    private fun posted() = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id.toLong() }

    @Test
    fun `prompts only for calls still undecided after the CRM lookup`() = runTest {
        repos.crmDirectory["+919000000001"] = CrmMatch(CrmRecordType.LEAD, "lead_1", "Vikram", null)
        repos.addCall(1, number = "+919000000001", startedAt = justNow) // CRM → BUSINESS, no prompt
        repos.addCall(2, number = "+919000000002", startedAt = justNow) // unknown → prompt
        repos.addCall(3, number = "+919000000003", startedAt = now - TimeUnit.HOURS.toMillis(5)) // too old

        val result = worker(prompt = true, callIds = longArrayOf(1, 2, 3)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
        assertEquals(listOf(2L), posted())
    }

    @Test
    fun `offline still prompts, then hands lookups to the network-bound retry work`() = runTest {
        repos.crmAvailable = false
        repos.addCall(1, startedAt = justNow)

        val result = worker(prompt = true, callIds = longArrayOf(1)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1L), posted())
        val retry = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CrmLookupScheduler.RETRY).get()
        assertEquals(WorkInfo.State.ENQUEUED, retry.single().state)
    }

    @Test
    fun `the retry work backs off while offline and never prompts`() = runTest {
        repos.crmAvailable = false
        repos.addCall(1, startedAt = justNow)

        assertEquals(ListenableWorker.Result.retry(), worker(prompt = false, callIds = longArrayOf(1)).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(prompt = true, callIds = longArrayOf(1), attempt = 1).doWork())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a call the CRM resolves later loses its prompt`() = runTest {
        repos.addCall(1, startedAt = justNow)
        notifier.show(repos.getCall(1)!!)
        repos.crmDirectory["+919876543210"] = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya", null)

        worker(prompt = false, callIds = longArrayOf()).doWork()

        assertTrue(posted().isEmpty())
    }
}
