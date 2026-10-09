package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CachedCrmLookup
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
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
import java.util.concurrent.TimeUnit

class ResolveCrmMatchesUseCaseTest {

    private val now = 1_790_000_000_000L
    private val recent = now - TimeUnit.MINUTES.toMillis(5)
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val syncGate = SyncGate(repos, repos, repos, clock)
    private val reclassify = ReclassifyNumberUseCase(
        repos, repos, repos, GatherClassificationSignals(repos, repos), ClassifyCallUseCase(), syncGate, repos,
    )
    private val resolve = ResolveCrmMatchesUseCase(repos, repos, reclassify, clock)

    private val ananya = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya Sharma", "Sharma Traders")

    @Test
    fun `a CRM match turns an unclassified call BUSINESS and queues it`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        val call = repos.addCall(1, startedAt = recent)

        val result = resolve()

        val updated = repos.getCall(call.id)!!
        assertEquals(Classification.BUSINESS, updated.classification)
        assertEquals(ClassificationSource.CRM_MATCH, updated.classificationSource)
        assertEquals(ananya, updated.crmMatch)
        assertEquals(setOf(call.id), result.reclassifiedCallIds)
        assertTrue(call.id in repos.outbox)
    }

    @Test
    fun `CRM match beats device contact, which would otherwise be PERSONAL`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        repos.addCall(1, startedAt = recent, classification = Classification.PERSONAL,
            source = ClassificationSource.DEVICE_CONTACT, deviceContactName = "Ananya")

        resolve()

        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
    }

    @Test
    fun `no match keeps the call UNCLASSIFIED, caches the answer and queues nothing`() = runTest {
        repos.addCall(1, startedAt = recent)

        val result = resolve()

        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertTrue(result.reclassifiedCallIds.isEmpty())
        assertNull(repos.crmCache.getValue("+919876543210").match)
        assertTrue(repos.outbox.isEmpty())
    }

    @Test
    fun `a call the user classified by hand is never overridden by the CRM`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        repos.addCall(1, startedAt = recent, classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        repos.addCall(2, startedAt = recent)

        resolve()

        assertEquals(ClassificationSource.USER_MANUAL, repos.getCall(1)!!.classificationSource)
        assertEquals(ClassificationSource.CRM_MATCH, repos.getCall(2)!!.classificationSource)
        assertEquals(ananya, repos.getCall(1)!!.crmMatch) // still shown as the CRM contact
    }

    @Test
    fun `numbers the user marked personal are never sent to the CRM`() = runTest {
        repos.setRule("+919000000001", Classification.PERSONAL, now)
        repos.addCall(1, number = "+919000000001", startedAt = recent,
            classification = Classification.PERSONAL, source = ClassificationSource.USER_RULE)
        repos.addCall(2, number = "+919000000002", startedAt = recent,
            classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)
        repos.addCall(3, number = "+919000000002", startedAt = recent) // same number, other call

        resolve()

        assertTrue(repos.crmLookups.isEmpty())
    }

    @Test
    fun `a user rule outranks a CRM match`() = runTest {
        // Both say BUSINESS here; the source must stay USER_RULE (personal-rule numbers are never looked up at all).
        repos.crmCache["+919876543210"] = CachedCrmLookup(ananya, now - TimeUnit.DAYS.toMillis(2))
        repos.crmDirectory["+919876543210"] = ananya
        repos.setRule("+919876543210", Classification.BUSINESS, now)
        repos.addCall(1, startedAt = recent)

        resolve()

        assertEquals(ClassificationSource.USER_RULE, repos.getCall(1)!!.classificationSource)
    }

    @Test
    fun `fresh cache answers are not looked up again, stale ones are`() = runTest {
        repos.crmCache["+919000000001"] = CachedCrmLookup(null, now - TimeUnit.HOURS.toMillis(1))
        repos.crmCache["+919000000002"] = CachedCrmLookup(null, now - TimeUnit.HOURS.toMillis(30))
        repos.addCall(1, number = "+919000000001", startedAt = recent)
        repos.addCall(2, number = "+919000000002", startedAt = recent)

        resolve()

        assertEquals(listOf("+919000000002"), repos.crmLookups)
    }

    @Test
    fun `calls older than the lookup window are not looked up`() = runTest {
        repos.addCall(1, startedAt = now - TimeUnit.DAYS.toMillis(8))

        resolve()

        assertTrue(repos.crmLookups.isEmpty())
    }

    @Test
    fun `CRM unreachable stops early, caches nothing and reports it`() = runTest {
        repos.crmAvailable = false
        repos.addCall(1, number = "+919000000001", startedAt = recent)
        repos.addCall(2, number = "+919000000002", startedAt = recent - 1)

        val result = resolve()

        assertTrue(result.unavailable)
        assertEquals(1, repos.crmLookups.size)
        assertTrue(repos.crmCache.isEmpty())
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
    }

    @Test
    fun `a number removed from the CRM stops being BUSINESS and is dequeued`() = runTest {
        repos.crmCache["+919876543210"] = CachedCrmLookup(ananya, now - TimeUnit.HOURS.toMillis(30))
        repos.addCall(1, startedAt = recent, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        syncGate.reconcile(1)
        assertTrue(1L in repos.outbox)

        val result = resolve() // directory no longer has it

        assertFalse(result.unavailable)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertFalse(1L in repos.outbox)
    }
}
