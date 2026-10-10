package com.example.calltracker.ui.calls

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.sync.SweepRequester
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.MainDispatcherRule
import com.example.calltracker.testing.TestUseCases
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CallListViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)
    private var sweeps = 0
    private val sweepRequester = object : SweepRequester {
        override fun requestSweep() {
            sweeps++
        }
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        CallListViewModel(repos, repos, sweepRequester, saved)

    private suspend fun seed() {
        repos.addCall(1, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        repos.addCall(2, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        repos.addCall(3, classification = Classification.PERSONAL, source = ClassificationSource.DEVICE_CONTACT)
        repos.addCall(4)
        useCases.syncGate.reconcile(1)
        useCases.syncGate.reconcile(2)
        repos.markSynced(2)
    }

    @Test
    fun `shows every call newest first with its sync state`() = runTest {
        seed()

        val rows = viewModel().calls.asSnapshot()

        assertEquals(listOf(4L, 3L, 2L, 1L), rows.map { it.call.id })
        assertEquals(listOf(null, null, SyncStatus.SYNCED, SyncStatus.PENDING), rows.map { it.syncStatus })
    }

    @Test
    fun `a tab shows its calls, whatever their upload status`() = runTest {
        seed()
        val vm = viewModel()

        vm.setClassificationFilter(Classification.BUSINESS)
        assertEquals("pending and synced alike", listOf(2L, 1L), vm.calls.asSnapshot().map { it.call.id })
        assertTrue(vm.state.first { it.filter.classification != null }.filter.isActive)

        vm.setClassificationFilter(null)
        assertEquals(4, vm.calls.asSnapshot().size)
    }

    @Test
    fun `list updates when a call changes classification`() = runTest {
        seed()
        val vm = viewModel()
        vm.setClassificationFilter(Classification.UNCLASSIFIED)
        assertEquals(listOf(4L), vm.calls.asSnapshot().map { it.call.id })

        useCases.setClassification(4, Classification.BUSINESS, applyToNumber = false)

        assertTrue(vm.calls.asSnapshot().isEmpty())
    }

    @Test
    fun `upload summary counts every call, whatever the filter`() = runTest {
        seed()
        useCases.syncGate.reconcile(3) // personal: never queued
        repos.addCall(5, classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        useCases.syncGate.reconcile(5)
        repos.setSyncStatus(5, SyncStatus.FAILED_PERMANENT)
        val vm = viewModel()
        vm.setClassificationFilter(Classification.PERSONAL)

        val state = vm.state.first { it.waitingToUpload > 0 }

        assertEquals(1, state.waitingToUpload)
        assertEquals(1, state.uploadFailed)
    }

    @Test
    fun `filter survives process death through SavedStateHandle`() = runTest {
        seed()
        val saved = SavedStateHandle()
        viewModel(saved).setClassificationFilter(Classification.PERSONAL)

        val restored = viewModel(saved)

        assertEquals(Classification.PERSONAL, restored.state.first { it.filter.classification != null }.filter.classification)
        assertEquals(listOf(3L), restored.calls.asSnapshot().map { it.call.id })
    }

    @Test
    fun `battery hint can be dismissed for good`() = runTest {
        val vm = viewModel()
        assertFalse(vm.state.first { !it.batteryHintDismissed }.batteryHintDismissed)

        vm.dismissBatteryHint()

        assertTrue(vm.state.first { it.batteryHintDismissed }.batteryHintDismissed)
        assertTrue("a new screen remembers it", viewModel().state.first { it.batteryHintDismissed }.batteryHintDismissed)
    }

    @Test
    fun `refresh requests a sweep`() {
        viewModel().refresh()
        assertEquals(1, sweeps)
    }
}
