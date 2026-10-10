package com.example.calltracker.ui.rules

import app.cash.turbine.test
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.MainDispatcherRule
import com.example.calltracker.testing.TestUseCases
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RulesViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)
    // Lazy: built inside the test, after MainDispatcherRule has installed the test Main dispatcher.
    private val viewModel by lazy { RulesViewModel(repos, useCases.setRule, useCases.deleteRule) }

    @Test
    fun `adding a rule normalises the number and applies it to existing calls`() = runTest {
        repos.addCall(1)
        repos.addCall(2, classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)

        viewModel.events.test {
            viewModel.addRule("098765 43210", Classification.BUSINESS)
            assertEquals(RulesEvent.Saved(updatedCalls = 1), awaitItem())
        }
        assertEquals(listOf("+919876543210"), viewModel.state.first { !it.loading }.rules.map { it.normalizedNumber })
        assertEquals(ClassificationSource.USER_RULE, repos.getCall(1)!!.classificationSource)
        assertTrue("now queued", 1L in repos.outbox)
        assertEquals("hand-classified call untouched", ClassificationSource.USER_MANUAL, repos.getCall(2)!!.classificationSource)
    }

    @Test
    fun `an invalid number is rejected`() = runTest {
        viewModel.events.test {
            viewModel.addRule("12", Classification.PERSONAL)
            assertEquals(RulesEvent.InvalidNumber, awaitItem())
        }
        assertTrue(repos.observeRules().first().isEmpty())
    }

    @Test
    fun `switching a rule to personal dequeues its calls`() = runTest {
        repos.addCall(1)
        useCases.setRule("+919876543210", Classification.BUSINESS)
        assertTrue(1L in repos.outbox)

        viewModel.switch(repos.getRule("+919876543210")!!)

        assertEquals(Classification.PERSONAL, repos.getCall(1)!!.classification)
        assertFalse(1L in repos.outbox)
    }

    @Test
    fun `deleting a saved answer makes the number's calls unsorted again`() = runTest {
        repos.addCall(1, deviceContactName = "Mom")
        useCases.setRule("+919876543210", Classification.BUSINESS)

        viewModel.events.test {
            viewModel.delete(repos.getRule("+919876543210")!!)
            assertEquals(RulesEvent.Deleted(updatedCalls = 1), awaitItem())
        }
        assertNull(repos.getRule("+919876543210"))
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertEquals(ClassificationSource.NONE, repos.getCall(1)!!.classificationSource)
        assertFalse(1L in repos.outbox)
    }
}
