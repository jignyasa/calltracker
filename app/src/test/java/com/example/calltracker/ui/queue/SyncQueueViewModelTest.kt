package com.example.calltracker.ui.queue

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.SyncRequester
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.MainDispatcherRule
import com.example.calltracker.testing.TestUseCases
import com.example.calltracker.ui.calls.SyncFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SyncQueueViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)
    private var uploadsNow = 0
    private val syncRequester = object : SyncRequester {
        override fun requestSync() = Unit
        override fun syncNow() {
            uploadsNow++
        }
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = SyncQueueViewModel(repos, syncRequester, saved)

    /** 1 pending, 2 synced, 3 failed, 4 personal (never queued), 5 being retried (counts as pending). */
    private suspend fun seed() {
        (1L..3L).forEach { id ->
            repos.addCall(id, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
            useCases.syncGate.reconcile(id)
        }
        repos.markSynced(2)
        repos.setSyncStatus(3, SyncStatus.FAILED_PERMANENT)
        repos.addCall(4, classification = Classification.PERSONAL, source = ClassificationSource.DEVICE_CONTACT)
        useCases.syncGate.reconcile(4)
        repos.addCall(5, classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        useCases.syncGate.reconcile(5)
        repos.setSyncStatus(5, SyncStatus.FAILED_RETRYABLE)
    }

    @Test
    fun `opens on the pending tab, which includes calls being retried`() = runTest {
        seed()
        val vm = viewModel()

        assertEquals(SyncFilter.PENDING, vm.state.value.tab)
        assertEquals(listOf(5L, 1L), vm.calls.asSnapshot().map { it.call.id })
    }

    @Test
    fun `each tab lists only its upload state, and personal calls never appear`() = runTest {
        seed()
        val vm = viewModel()

        vm.selectTab(SyncFilter.FAILED)
        assertEquals(listOf(3L), vm.calls.asSnapshot().map { it.call.id })

        vm.selectTab(SyncFilter.SYNCED)
        assertEquals(listOf(2L), vm.calls.asSnapshot().map { it.call.id })
    }

    @Test
    fun `tab counts cover the whole queue`() = runTest {
        seed()

        val state = viewModel().state.first { it.pending > 0 }

        assertEquals(2, state.pending)
        assertEquals(1, state.failed)
    }

    @Test
    fun `tab survives process death through SavedStateHandle`() = runTest {
        seed()
        val saved = SavedStateHandle()
        viewModel(saved).selectTab(SyncFilter.FAILED)

        val restored = viewModel(saved)

        assertEquals(SyncFilter.FAILED, restored.state.first { it.tab == SyncFilter.FAILED }.tab)
        assertEquals(listOf(3L), restored.calls.asSnapshot().map { it.call.id })
    }

    @Test
    fun `upload now skips the debounce`() {
        viewModel().uploadNow()

        assertEquals(1, uploadsNow)
    }
}
