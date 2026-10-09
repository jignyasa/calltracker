package com.example.calltracker.ui.calls

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.sync.SweepRequester
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.MainDispatcherRule
import com.example.calltracker.testing.TestUseCases
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
    private val requester = object : SweepRequester {
        override fun requestSweep() {
            sweeps++
        }
    }

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
        val viewModel = CallListViewModel(repos, requester, SavedStateHandle())

        viewModel.state.test {
            val state = expectMostRecentItem()
            assertFalse(state.loading)
            assertEquals(listOf(4L, 3L, 2L, 1L), state.calls.map { it.call.id })
            assertEquals(
                listOf(null, null, SyncStatus.SYNCED, SyncStatus.PENDING),
                state.calls.map { it.syncStatus },
            )
        }
    }

    @Test
    fun `classification and sync filters combine`() = runTest {
        seed()
        val viewModel = CallListViewModel(repos, requester, SavedStateHandle())

        viewModel.state.test {
            skipItems(1)
            viewModel.setClassificationFilter(Classification.BUSINESS)
            assertEquals(listOf(2L, 1L), expectMostRecentItem().calls.map { it.call.id })

            viewModel.setSyncFilter(SyncFilter.PENDING)
            val state = expectMostRecentItem()
            assertEquals(listOf(1L), state.calls.map { it.call.id })
            assertTrue(state.filter.isActive)

            viewModel.setClassificationFilter(null)
            viewModel.setSyncFilter(null)
            assertEquals(4, expectMostRecentItem().calls.size)
        }
    }

    @Test
    fun `list updates when a call changes classification`() = runTest {
        seed()
        val viewModel = CallListViewModel(repos, requester, SavedStateHandle())
        viewModel.setClassificationFilter(Classification.UNCLASSIFIED)

        viewModel.state.test {
            assertEquals(listOf(4L), expectMostRecentItem().calls.map { it.call.id })
            useCases.setClassification(4, Classification.BUSINESS, applyToNumber = false)
            assertTrue(expectMostRecentItem().calls.isEmpty())
        }
    }

    @Test
    fun `filter survives process death through SavedStateHandle`() = runTest {
        seed()
        val saved = SavedStateHandle()
        CallListViewModel(repos, requester, saved).setClassificationFilter(Classification.PERSONAL)

        val restored = CallListViewModel(repos, requester, saved)

        restored.state.test {
            val state = expectMostRecentItem()
            assertEquals(Classification.PERSONAL, state.filter.classification)
            assertEquals(listOf(3L), state.calls.map { it.call.id })
        }
    }

    @Test
    fun `refresh requests a sweep`() {
        CallListViewModel(repos, requester, SavedStateHandle()).refresh()
        assertEquals(1, sweeps)
    }
}
