package com.example.calltracker.ui.settings

import app.cash.turbine.test
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SimCard
import com.example.calltracker.domain.repository.SimCardSource
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
import java.util.concurrent.TimeUnit

class SettingsViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val now = 1_790_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)
    private var sims = listOf(SimCard(0, "Airtel"), SimCard(1, "Jio"))
    private val simSource = object : SimCardSource {
        override suspend fun activeSims() = sims
    }

    private fun viewModel() = SettingsViewModel(repos, simSource, useCases.setWorkSim)

    @Test
    fun `lists the SIMs and the current work SIM`() = runTest {
        repos.setWorkSimSlot(1)

        viewModel().state.test {
            val state = expectMostRecentItem()
            assertFalse(state.loading)
            assertEquals(listOf("Airtel", "Jio"), state.sims.map { it.label })
            assertEquals(1, state.workSimSlot)
        }
    }

    @Test
    fun `choosing a work SIM reclassifies recent calls on it, but not hand-picked or old ones`() = runTest {
        val recent = now - TimeUnit.HOURS.toMillis(2)
        repos.addCall(1, number = "+919000000001", simSlot = 1, startedAt = recent)
        repos.addCall(2, number = "+919000000002", simSlot = 0, startedAt = recent)
        repos.addCall(3, number = "+919000000003", simSlot = 1, startedAt = recent,
            classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)
        repos.addCall(4, number = "+919000000004", simSlot = 1, startedAt = now - TimeUnit.DAYS.toMillis(30))
        val vm = viewModel()

        vm.events.test {
            vm.selectWorkSim(1)
            assertEquals(SettingsEvent.WorkSimUpdated(updatedCalls = 1), awaitItem())
        }
        assertEquals(ClassificationSource.WORK_SIM, repos.getCall(1)!!.classificationSource)
        assertTrue(1L in repos.outbox)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(2)!!.classification)
        assertEquals(ClassificationSource.USER_MANUAL, repos.getCall(3)!!.classificationSource)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(4)!!.classification)
        assertEquals(1, repos.workSimSlot())
    }

    @Test
    fun `clearing the work SIM takes those calls back out of the outbox`() = runTest {
        repos.addCall(1, simSlot = 1, startedAt = now - TimeUnit.HOURS.toMillis(1))
        val vm = viewModel()
        vm.selectWorkSim(1)
        assertTrue(1L in repos.outbox)

        vm.selectWorkSim(null)

        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertFalse(1L in repos.outbox)
    }

    @Test
    fun `refreshing picks up a swapped SIM`() = runTest {
        val vm = viewModel()
        sims = listOf(SimCard(0, "Vi"))

        vm.refreshSims()

        vm.state.test { assertEquals(listOf("Vi"), expectMostRecentItem().sims.map { it.label }) }
    }
}
