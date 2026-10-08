package com.example.calltracker.domain.usecase

import com.example.calltracker.data.phone.LibPhoneNumberNormalizer
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.testing.FakeCallLogSource
import com.example.calltracker.testing.FakeRepositories
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class SweepCallLogUseCaseTest {

    private val now = 1_790_000_000_000L
    private val minute = TimeUnit.MINUTES.toMillis(1)
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val callLog = FakeCallLogSource()
    private val sweep = SweepCallLogUseCase(
        source = callLog,
        calls = repos,
        rules = repos,
        sweepState = repos,
        normalizer = LibPhoneNumberNormalizer(),
        classify = ClassifyCallUseCase(),
        syncGate = SyncGate(repos, repos, repos, clock),
        transaction = repos,
        clock = clock,
    )

    @Test
    fun `first sweep only imports the backfill window`() = runTest {
        callLog.add(id = 1, startedAt = now - TimeUnit.DAYS.toMillis(8))
        callLog.add(id = 2, startedAt = now - TimeUnit.DAYS.toMillis(6))

        val result = sweep() as SweepCallLogUseCase.Result.Completed

        assertEquals(1, result.imported.size)
        assertEquals(listOf(2L), repos.allCalls.map { it.callLogId })
        assertEquals(SweepWatermark(lastCallLogId = 2, lastStartedAt = now - TimeUnit.DAYS.toMillis(6)), repos.watermark)
    }

    @Test
    fun `numbers are normalized and classified on import`() = runTest {
        callLog.add(id = 1, startedAt = now - minute, number = "098765 43210")
        callLog.add(id = 2, startedAt = now - minute, number = "9000000001", cachedName = "Mom")
        callLog.add(id = 3, startedAt = now - minute, number = "-2")

        sweep()

        val (unknown, contact, private) = repos.allCalls
        assertEquals("+919876543210", unknown.normalizedNumber)
        assertEquals(Classification.UNCLASSIFIED, unknown.classification)
        assertEquals(ClassificationSource.DEVICE_CONTACT, contact.classificationSource)
        assertEquals(Classification.PERSONAL, contact.classification)
        assertNull(private.normalizedNumber)
        assertTrue("nothing automatic may be queued without a business signal", repos.outbox.isEmpty())
    }

    @Test
    fun `calls from a business-rule number are queued immediately`() = runTest {
        repos.setRule("+919876543210", Classification.BUSINESS, now)
        callLog.add(id = 1, startedAt = now - minute, number = "9876543210", cachedName = "Saved as contact")

        sweep()

        val call = repos.allCalls.single()
        assertEquals(ClassificationSource.USER_RULE, call.classificationSource)
        assertEquals(setOf(call.id), repos.outbox.keys)
    }

    @Test
    fun `re-sweeping is idempotent`() = runTest {
        callLog.add(id = 1, startedAt = now - minute)
        sweep()
        val second = sweep() as SweepCallLogUseCase.Result.Completed

        assertTrue(second.imported.isEmpty())
        assertEquals(1, repos.allCalls.size)
    }

    @Test
    fun `long call written after a later short call is still picked up by id`() = runTest {
        // Short call B started at 10:05 and was logged first; long call A started at 10:00 and was logged at 10:30.
        callLog.add(id = 10, startedAt = now - 25 * minute)
        sweep()
        callLog.add(id = 11, startedAt = now - 30 * minute, durationSec = 1800)

        val result = sweep() as SweepCallLogUseCase.Result.Completed

        assertEquals(1, result.imported.size)
        assertEquals(listOf(10L, 11L), repos.allCalls.map { it.callLogId })
    }

    @Test
    fun `new calls after the user cleared the call log are found by date`() = runTest {
        callLog.add(id = 500, startedAt = now - 10 * minute)
        sweep()
        callLog.rows.clear()
        callLog.add(id = 1, startedAt = now - minute) // ids restart after a wipe

        val result = sweep() as SweepCallLogUseCase.Result.Completed

        assertEquals(1, result.imported.size)
    }

    @Test
    fun `large backlog is processed in pages`() = runTest {
        val total = SweepCallLogUseCase.PAGE_SIZE * 2 + 5
        repeat(total) { callLog.add(id = it + 1L, startedAt = now - TimeUnit.HOURS.toMillis(1) + it) }

        val result = sweep() as SweepCallLogUseCase.Result.Completed

        assertEquals(total, result.imported.size)
        assertEquals(3, callLog.requests.size)
        assertEquals(total.toLong(), repos.watermark!!.lastCallLogId)
    }

    @Test
    fun `missing permission reports it and keeps the watermark`() = runTest {
        callLog.permissionGranted = false

        assertEquals(SweepCallLogUseCase.Result.PermissionDenied, sweep())
        assertNull(repos.watermark)
    }
}
