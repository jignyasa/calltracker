package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.NewLead
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.TestUseCases
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CreateLeadFromCallUseCaseTest {

    private val now = 1_790_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val createLead = TestUseCases(repos, clock).createLead

    @Test
    fun `creating a lead makes the number's calls BUSINESS and queues them`() = runTest {
        repos.addCall(1)
        repos.addCall(2)

        val result = createLead(1, "  Vikram Rao ", " Rao Pharma ")

        assertTrue(result is CreateLeadFromCallUseCase.Result.Created)
        assertEquals(NewLead("Vikram Rao", "+919876543210", "Rao Pharma"), repos.createLeadRequests.single().first)
        for (id in listOf(1L, 2L)) {
            assertEquals(Classification.BUSINESS, repos.getCall(id)!!.classification)
            assertTrue(id in repos.outbox)
        }
        assertEquals("Vikram Rao", repos.getCall(1)!!.crmMatch?.name)
    }

    @Test
    fun `a business call uploaded without a lead is re-sent linked to the new lead`() = runTest {
        // Unknown caller: marked Business by hand, notes uploaded, then "Create lead".
        repos.addCall(1, classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        TestUseCases(repos, clock).saveLog(1, ActivityLog(notes = "Asked for a quote"))
        repos.markSynced(1)
        val before = repos.getActivity(1)!!

        createLead(1, "Vikram Rao", null)

        val after = repos.getActivity(1)!!
        assertEquals(CrmRecordType.LEAD, repos.getCall(1)!!.crmMatch?.type)
        assertEquals(SyncStatus.PENDING, after.syncStatus)
        assertEquals(before.clientId, after.clientId)
        assertEquals("Asked for a quote", after.log.notes)
        assertTrue(after.updatedAt > before.updatedAt) // or the CRM would answer CONFLICT, not UPDATED
    }

    @Test
    fun `a number already in the CRM is linked instead`() = runTest {
        val existing = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya Sharma", null)
        repos.crmDirectory["+919876543210"] = existing
        repos.addCall(1)

        val result = createLead(1, "Someone Else", null)

        assertEquals(CreateLeadFromCallUseCase.Result.AlreadyInCrm(existing), result)
        assertEquals(existing, repos.getCall(1)!!.crmMatch)
        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
    }

    @Test
    fun `a call the user had marked personal becomes business by hand`() = runTest {
        repos.addCall(1, classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)

        createLead(1, "Vikram Rao", null)

        val call = repos.getCall(1)!!
        assertEquals(Classification.BUSINESS, call.classification)
        assertEquals(ClassificationSource.USER_MANUAL, call.classificationSource)
    }

    @Test
    fun `offline leaves everything untouched and can be retried with the same key`() = runTest {
        repos.addCall(1)
        repos.crmAvailable = false

        assertEquals(CreateLeadFromCallUseCase.Result.Unavailable, createLead(1, "Vikram Rao", null))
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertTrue(repos.outbox.isEmpty())

        repos.crmAvailable = true
        createLead(1, "Vikram Rao", null)
        val (first, second) = repos.createLeadRequests
        assertEquals(first.second, second.second)
    }

    @Test
    fun `changed details get a new idempotency key`() {
        val a = CreateLeadFromCallUseCase.idempotencyKey(NewLead("A", "+919876543210", null))
        val b = CreateLeadFromCallUseCase.idempotencyKey(NewLead("B", "+919876543210", null))
        assertNotEquals(a, b)
    }

    @Test
    fun `validation errors and missing input are reported without side effects`() = runTest {
        repos.addCall(1)
        repos.addCall(2, number = null)
        repos.createLeadResult = { _, _ -> CreateLeadResult.Rejected("VALIDATION_FAILED") }

        assertEquals(CreateLeadFromCallUseCase.Result.NameRequired, createLead(1, "   ", null))
        assertEquals(CreateLeadFromCallUseCase.Result.NoNumber, createLead(2, "Vikram", null))
        assertEquals(CreateLeadFromCallUseCase.Result.CallNotFound, createLead(99, "Vikram", null))
        assertEquals(CreateLeadFromCallUseCase.Result.Rejected("VALIDATION_FAILED"), createLead(1, "Vikram", null))
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
    }
}
