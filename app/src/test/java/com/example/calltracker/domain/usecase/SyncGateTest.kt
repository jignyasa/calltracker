package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.testing.FakeRepositories
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SyncGateTest {

    private val repos = FakeRepositories()
    private val gate = SyncGate(repos, repos, repos, Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC))

    @Test
    fun `personal and unclassified calls never reach the outbox`() = runTest {
        repos.addCall(1, classification = Classification.PERSONAL)
        repos.addCall(2, classification = Classification.UNCLASSIFIED)

        assertFalse(gate.reconcile(1))
        assertFalse(gate.reconcile(2, contentChanged = true))

        assertTrue(repos.outbox.isEmpty())
        assertNull(repos.getActivity(1))
    }

    @Test
    fun `business call gets an activity and one outbox item`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)

        assertTrue(gate.reconcile(1))

        val activity = repos.getActivity(1)!!
        assertEquals(activity.clientId, repos.outbox.getValue(1).clientId)
        assertEquals(SyncStatus.PENDING, activity.syncStatus)
    }

    @Test
    fun `reconciling an unchanged synced call does not resend it`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)
        gate.reconcile(1)
        repos.markSynced(1)

        gate.reconcile(1)

        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
        assertEquals(1, repos.outbox.getValue(1).generation)
    }

    @Test
    fun `content change re-arms a synced item with the same client id`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)
        gate.reconcile(1)
        val clientId = repos.outbox.getValue(1).clientId
        repos.markSynced(1)

        gate.reconcile(1, contentChanged = true)

        val item = repos.outbox.getValue(1)
        assertEquals(SyncStatus.PENDING, item.status)
        assertEquals(2, item.generation)
        assertEquals(clientId, item.clientId)
    }

    @Test
    fun `call that becomes personal is dequeued if not yet synced`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS)
        gate.reconcile(1)

        repos.setClassification(1, Classification.PERSONAL, com.example.calltracker.domain.model.ClassificationSource.USER_MANUAL)
        gate.reconcile(1)

        assertTrue(repos.outbox.isEmpty())
    }

    @Test
    fun `unknown call id is a no-op`() = runTest {
        assertFalse(gate.reconcile(42))
        assertTrue(repos.outbox.isEmpty())
    }
}
