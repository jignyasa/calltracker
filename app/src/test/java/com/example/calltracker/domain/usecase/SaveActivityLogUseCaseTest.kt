package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.testing.FakeRepositories
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SaveActivityLogUseCaseTest {

    private val repos = FakeRepositories()
    private val clock = Clock.fixed(Instant.ofEpochMilli(9_000), ZoneOffset.UTC)
    private val gate = SyncGate(repos, repos, repos, clock)
    private val save = SaveActivityLogUseCase(repos, repos, gate, repos, clock)

    @Test
    fun `saving a log on a business call stores it trimmed and queues it`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)

        val result = save(1, ActivityLog(notes = "  Wants a quote  ", outcome = CallOutcome.INTERESTED, remarks = "   "))

        assertEquals(SaveActivityLogUseCase.Result.Saved, result)
        val activity = repos.getActivity(1)!!
        assertEquals("Wants a quote", activity.log.notes)
        assertNull(activity.log.remarks)
        assertEquals(9_000L, activity.updatedAt)
        assertEquals(SyncStatus.PENDING, activity.syncStatus)
    }

    @Test
    fun `editing after sync re-queues the same activity`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)
        save(1, ActivityLog(notes = "first"))
        val clientId = repos.getActivity(1)!!.clientId
        repos.markSynced(1)

        save(1, ActivityLog(notes = "second"))

        val item = repos.outbox.getValue(1)
        assertEquals(SyncStatus.PENDING, item.status)
        assertEquals(clientId, item.clientId)
    }

    @Test
    fun `logs on personal or unclassified calls are refused and nothing is queued`() = runTest {
        repos.addCall(1, classification = Classification.PERSONAL)
        repos.addCall(2, classification = Classification.UNCLASSIFIED)

        assertEquals(SaveActivityLogUseCase.Result.NotBusiness, save(1, ActivityLog(notes = "x")))
        assertEquals(SaveActivityLogUseCase.Result.NotBusiness, save(2, ActivityLog(notes = "x")))
        assertTrue(repos.outbox.isEmpty())
        assertNull(repos.getActivity(1))
    }

    @Test
    fun `over-long fields are rejected before touching storage`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)
        val long = "a".repeat(ActivityLog.MAX_TEXT_LENGTH + 1)

        val result = save(1, ActivityLog(notes = long, nextAction = long))

        assertEquals(
            SaveActivityLogUseCase.Result.Invalid(setOf(SaveActivityLogUseCase.Field.NOTES, SaveActivityLogUseCase.Field.NEXT_ACTION)),
            result,
        )
        assertNull(repos.getActivity(1))
    }

    @Test
    fun `unknown call returns CallNotFound`() = runTest {
        assertEquals(SaveActivityLogUseCase.Result.CallNotFound, save(7, ActivityLog()))
    }
}
