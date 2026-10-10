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
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.TestUseCases
import com.example.calltracker.ui.notifications.PostCallNotifier
import com.example.calltracker.ui.postcall.PostCallLogOffer
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

    /** Records the log-form offers (the real one starts PostCallActivity). */
    private val offered = mutableListOf<Long>()
    private val offers = object : PostCallLogOffer {
        override fun offer(call: com.example.calltracker.domain.model.CallRecord) {
            offered += call.id
        }
    }
    private val justNow = now - TimeUnit.MINUTES.toMillis(3)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannel()
    }

    private fun worker(prompt: Boolean, callIds: LongArray, attempt: Int = 0): CrmLookupWorker {
        val resolve = TestUseCases(repos, clock).resolveCrm
        return TestListenableWorkerBuilder<CrmLookupWorker>(context)
            .setInputData(workDataOf(CrmLookupWorker.KEY_PROMPT to prompt, CrmLookupWorker.KEY_CALL_IDS to callIds))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    CrmLookupWorker(appContext, params, resolve, repos, notifier, offers, scheduler, clock)
            })
            .build()
    }

    private fun posted() = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id.toLong() }

    /** A call the number's saved answer made professional. */
    private suspend fun professionalCall(id: Long, number: String, startedAt: Long) {
        repos.setRule(number, Classification.BUSINESS, now)
        repos.addCall(id, number = number, startedAt = startedAt,
            classification = Classification.BUSINESS, source = ClassificationSource.USER_RULE)
    }

    @Test
    fun `asks about every call with no saved answer, a CRM customer's too - classification comes first`() = runTest {
        repos.crmDirectory["+919000000001"] = CrmMatch(CrmRecordType.LEAD, "lead_1", "Vikram", null)
        repos.addCall(1, number = "+919000000001", startedAt = justNow) // in the CRM, but not answered yet → asked
        professionalCall(2, "+919000000002", justNow)                    // saved answer → not asked
        repos.addCall(3, number = "+919000000003", startedAt = now - TimeUnit.HOURS.toMillis(5)) // too old

        val result = worker(prompt = true, callIds = longArrayOf(1, 2, 3)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertEquals(listOf(1L), posted())
        assertEquals("only the professional number is looked up", listOf("+919000000002"), repos.crmLookups)
    }

    @Test
    fun `offline still prompts, then hands lookups to the network-bound retry work`() = runTest {
        repos.crmAvailable = false
        repos.addCall(1, startedAt = justNow)
        professionalCall(2, "+919000000002", justNow - 1) // needs a lookup

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
        professionalCall(2, "+919000000002", justNow - 1) // needs a lookup

        assertEquals(ListenableWorker.Result.retry(), worker(prompt = false, callIds = longArrayOf(1)).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(prompt = true, callIds = longArrayOf(1), attempt = 1).doWork())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `the CRM never decides an unsorted call - its prompt stays and the number isn't sent`() = runTest {
        repos.addCall(1, startedAt = justNow)
        notifier.show(repos.getCall(1)!!)
        repos.crmDirectory["+919876543210"] = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya", null)

        worker(prompt = false, callIds = longArrayOf()).doWork()

        assertEquals(listOf(1L), posted())
        assertTrue(offered.isEmpty())
        assertTrue(repos.crmLookups.isEmpty())
    }

    @Test
    fun `a professional call that just ended gets the log form, linked, not the question`() = runTest {
        repos.crmDirectory["+919000000001"] = CrmMatch(CrmRecordType.LEAD, "lead_1", "Test Customer", "Demo Pvt Ltd")
        professionalCall(1, "+919000000001", justNow)

        worker(prompt = true, callIds = longArrayOf(1)).doWork()

        assertEquals(listOf(1L), offered)
        assertEquals("lead_1", repos.getCall(1)!!.crmMatch?.id)
        assertTrue("no Work call? question for a number with a saved answer", posted().isEmpty())
    }

    @Test
    fun `only the newest business call is offered - old and undecided ones are not`() = runTest {
        repos.setRule("+919000000001", Classification.BUSINESS, now)
        repos.setRule("+919000000002", Classification.BUSINESS, now)
        repos.addCall(1, number = "+919000000001", startedAt = justNow - 60_000,
            classification = Classification.BUSINESS, source = ClassificationSource.USER_RULE)
        repos.addCall(2, number = "+919000000002", startedAt = justNow,
            classification = Classification.BUSINESS, source = ClassificationSource.USER_RULE)
        repos.addCall(3, number = "+919000000001", startedAt = now - TimeUnit.HOURS.toMillis(2),
            classification = Classification.BUSINESS, source = ClassificationSource.USER_RULE)
        repos.addCall(4, number = "+919000000004", startedAt = justNow) // unknown → question instead

        worker(prompt = true, callIds = longArrayOf(1, 2, 3, 4)).doWork()

        assertEquals(listOf(2L), offered)
        assertEquals(listOf(4L), posted())
    }

    @Test
    fun `a retried run never offers again`() = runTest {
        repos.crmDirectory["+919000000001"] = CrmMatch(CrmRecordType.LEAD, "lead_1", "Test Customer", null)
        professionalCall(1, "+919000000001", justNow)

        worker(prompt = true, callIds = longArrayOf(1), attempt = 1).doWork()

        assertTrue(offered.isEmpty())
    }

    @Test
    fun `never more than three prompts showing across runs - the newest calls keep theirs`() = runTest {
        (1L..5L).forEach { id -> repos.addCall(id, number = "+91900000000$id", startedAt = justNow + id) }

        worker(prompt = true, callIds = longArrayOf(1, 2)).doWork()
        worker(prompt = true, callIds = longArrayOf(3, 4)).doWork()
        worker(prompt = true, callIds = longArrayOf(5)).doWork()

        assertEquals(listOf(3L, 4L, 5L), posted().sorted())
    }

    @Test
    fun `an Add notes notification doesn't count as a prompt`() = runTest {
        repos.addCall(1, number = "+919000000001", classification = Classification.BUSINESS, startedAt = justNow)
        notifier.showLogPrompt(repos.getCall(1)!!)
        (2L..4L).forEach { id -> repos.addCall(id, number = "+91900000000$id", startedAt = justNow + id) }

        worker(prompt = true, callIds = longArrayOf(2, 3, 4)).doWork()

        assertEquals(listOf(1L, 2L, 3L, 4L), posted().sorted())
    }
}
