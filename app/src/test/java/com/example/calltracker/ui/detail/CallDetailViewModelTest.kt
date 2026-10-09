package com.example.calltracker.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.MainDispatcherRule
import com.example.calltracker.testing.TestUseCases
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

class CallDetailViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)

    private fun viewModel(callId: Long = 1) = CallDetailViewModel(
        SavedStateHandle(mapOf(CallDetailViewModel.ARG_CALL_ID to callId)),
        repos,
        repos,
        useCases.setClassification,
        useCases.saveLog,
        useCases.createLead,
    )

    private suspend fun businessCall(id: Long = 1) {
        repos.addCall(id, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        useCases.syncGate.reconcile(id)
    }

    @Test
    fun `loads the call, its activity and the sync state`() = runTest {
        businessCall()

        viewModel().state.test {
            val state = expectMostRecentItem()
            assertFalse(state.loading)
            assertEquals(1L, state.call?.id)
            assertEquals(SyncStatus.PENDING, state.activity?.syncStatus)
            assertTrue(state.canLog)
            assertFalse(state.dirty)
        }
    }

    @Test
    fun `unknown call reports not found`() = runTest {
        viewModel(callId = 42).state.test {
            assertTrue(expectMostRecentItem().notFound)
        }
    }

    @Test
    fun `editing makes the form dirty, saving stores a cleaned log and queues it again`() = runTest {
        businessCall()
        repos.markSynced(1)
        val vm = viewModel()

        vm.events.test {
            vm.onNotesChanged("  Asked for a quote  ")
            vm.onOutcomeChanged(CallOutcome.CALL_BACK)
            vm.onRemarksChanged("   ")
            assertTrue(vm.state.value.dirty)
            val versionBefore = vm.state.value.formVersion

            vm.save()

            assertEquals(CallDetailEvent.Saved, awaitItem())
            val state = vm.state.value
            assertEquals(ActivityLog(notes = "Asked for a quote", outcome = CallOutcome.CALL_BACK), state.draft)
            assertFalse(state.dirty)
            assertTrue("form refilled with the cleaned values", state.formVersion > versionBefore)
            assertEquals(SyncStatus.PENDING, repos.getActivity(1)?.syncStatus)
        }
    }

    @Test
    fun `too long notes are flagged and nothing is saved`() = runTest {
        businessCall()
        val vm = viewModel()

        vm.onNotesChanged("x".repeat(ActivityLog.MAX_TEXT_LENGTH + 1))
        vm.save()

        assertEquals(setOf(SaveActivityLogUseCase.Field.NOTES), vm.state.value.invalidFields)
        assertNull(repos.getActivity(1)?.log?.notes)
        vm.onNotesChanged("short")
        assertTrue("editing clears the error", vm.state.value.invalidFields.isEmpty())
    }

    @Test
    fun `a change from elsewhere refreshes the form, but never over unsaved typing`() = runTest {
        businessCall()
        val vm = viewModel()

        repos.updateLog(1, ActivityLog(notes = "from sync"), clock.millis())
        vm.state.test { assertEquals("from sync", expectMostRecentItem().draft.notes) }

        vm.onNotesChanged("my edit")
        repos.updateLog(1, ActivityLog(notes = "newer from sync"), clock.millis())
        vm.state.test {
            val state = expectMostRecentItem()
            assertEquals("my edit", state.draft.notes)
            assertTrue(state.dirty)
        }

        vm.discardChanges()
        assertEquals("newer from sync", vm.state.value.draft.notes)
    }

    @Test
    fun `the log form is only for business calls`() = runTest {
        repos.addCall(1)
        val vm = viewModel()

        vm.events.test {
            assertFalse(vm.state.value.canLog)
            vm.save()
            assertEquals(CallDetailEvent.NotBusiness, awaitItem())
        }
    }

    @Test
    fun `classifying with always-for-number creates a rule for every call from it`() = runTest {
        repos.addCall(1)
        repos.addCall(2)
        val vm = viewModel()

        vm.classify(Classification.BUSINESS, applyToNumber = true)

        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
        assertEquals(ClassificationSource.USER_RULE, repos.getCall(2)!!.classificationSource)
        assertEquals(Classification.BUSINESS, repos.getRule("+919876543210")?.classification)
        assertTrue(vm.state.value.canLog)
    }

    @Test
    fun `marking an uploaded call personal warns that the CRM keeps it`() = runTest {
        businessCall()
        repos.markSynced(1)
        val vm = viewModel()

        vm.events.test {
            vm.classify(Classification.PERSONAL, applyToNumber = false)
            assertEquals(CallDetailEvent.AlreadyUploaded, awaitItem())
        }
        assertFalse(vm.state.value.canLog)
    }

    @Test
    fun `create lead links the call to the new CRM record`() = runTest {
        repos.addCall(1)
        val vm = viewModel()
        assertTrue(vm.state.value.canCreateLead)

        vm.events.test {
            vm.createLead("Vikram Rao", "Rao Pharma")
            assertEquals(CallDetailEvent.LeadCreated("Vikram Rao"), awaitItem())
        }
        val state = vm.state.value
        assertEquals("Vikram Rao", state.call?.crmMatch?.name)
        assertEquals(Classification.BUSINESS, state.call?.classification)
        assertFalse(state.canCreateLead)
        assertFalse(state.creatingLead)
    }

    @Test
    fun `create lead offline tells the user and changes nothing`() = runTest {
        repos.addCall(1)
        repos.crmAvailable = false
        val vm = viewModel()

        vm.events.test {
            vm.createLead("Vikram Rao", null)
            assertEquals(CallDetailEvent.LeadUnavailable, awaitItem())
        }
        assertEquals(Classification.UNCLASSIFIED, vm.state.value.call?.classification)
    }
}
