package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CachedCrmLookup
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.testing.FakeRepositories
import com.example.calltracker.testing.TestUseCases
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
    private val useCases = TestUseCases(repos, clock)
    private val syncGate = useCases.syncGate
    private val resolve = useCases.resolveCrm

    private val ananya = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya Sharma", "Sharma Traders")

    /** A call the number's saved answer made professional, already queued like the sweep does. */
    private suspend fun professionalCall(
        id: Long,
        number: String = "+919876543210",
        startedAt: Long = recent,
        contactName: String? = null,
    ) {
        repos.setRule(number, Classification.BUSINESS, now)
        repos.addCall(id, number = number, startedAt = startedAt, classification = Classification.BUSINESS,
            source = ClassificationSource.USER_RULE, deviceContactName = contactName)
        syncGate.reconcile(id)
    }

    @Test
    fun `a professional call is linked to the CRM record found`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        professionalCall(1)

        resolve()

        val call = repos.getCall(1)!!
        assertEquals(ananya, call.crmMatch)
        assertEquals(Classification.BUSINESS, call.classification)
        assertEquals(ClassificationSource.USER_RULE, call.classificationSource)
        assertTrue(1L in repos.outbox)
    }

    @Test
    fun `unsorted calls, saved contacts included, are never sent to the CRM`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        repos.addCall(1, startedAt = recent)
        repos.addCall(2, number = "+919000000002", startedAt = recent, deviceContactName = "Ananya")

        val result = resolve()

        assertTrue(repos.crmLookups.isEmpty())
        assertEquals(0, result.lookedUp)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertNull("a CRM customer isn't recognised before the user says it's work", repos.getCall(1)!!.crmMatch)
    }

    @Test
    fun `no match caches the answer and the call stays professional`() = runTest {
        professionalCall(1)

        val result = resolve()

        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
        assertNull(repos.crmCache.getValue("+919876543210").match)
        assertEquals("no phonebook name: Create lead is offered instead", 0, result.leadsCreated)
        assertTrue(repos.createLeadRequests.isEmpty())
    }

    @Test
    fun `a call the user classified by hand keeps its classification and shows the CRM record`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        repos.addCall(1, startedAt = recent, classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)

        resolve()

        assertEquals(ClassificationSource.USER_MANUAL, repos.getCall(1)!!.classificationSource)
        assertEquals(ananya, repos.getCall(1)!!.crmMatch)
    }

    @Test
    fun `numbers the user marked personal are never sent to the CRM`() = runTest {
        repos.setRule("+919000000001", Classification.PERSONAL, now)
        repos.addCall(1, number = "+919000000001", startedAt = recent,
            classification = Classification.PERSONAL, source = ClassificationSource.USER_RULE)
        repos.addCall(2, number = "+919000000002", startedAt = recent,
            classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)
        // Same number, another call that is professional: the number was still marked personal once.
        repos.addCall(3, number = "+919000000002", startedAt = recent,
            classification = Classification.BUSINESS, source = ClassificationSource.WORK_SIM)

        resolve()

        assertTrue(repos.crmLookups.isEmpty())
    }

    @Test
    fun `fresh cache answers are not looked up again, stale ones are`() = runTest {
        repos.crmCache["+919000000001"] = CachedCrmLookup(null, now - TimeUnit.HOURS.toMillis(1))
        repos.crmCache["+919000000002"] = CachedCrmLookup(null, now - TimeUnit.HOURS.toMillis(30))
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2) // both calls ended before the answers came
        professionalCall(1, number = "+919000000001", startedAt = twoHoursAgo)
        professionalCall(2, number = "+919000000002", startedAt = twoHoursAgo)

        resolve()

        assertEquals(listOf("+919000000002"), repos.crmLookups)
    }

    @Test
    fun `a new call is searched again even when the cached answer is still fresh`() = runTest {
        // "Not in CRM" an hour ago; since then someone added the number as a contact and it called again.
        repos.crmCache["+919876543210"] = CachedCrmLookup(null, now - TimeUnit.HOURS.toMillis(1))
        repos.crmDirectory["+919876543210"] = ananya
        professionalCall(1, startedAt = now - TimeUnit.HOURS.toMillis(2))
        professionalCall(2)

        resolve()
        resolve() // the answer is now newer than every call: no second lookup

        assertEquals(listOf("+919876543210"), repos.crmLookups)
        assertEquals(ananya, repos.getCall(2)!!.crmMatch)
    }

    @Test
    fun `an uploaded business call found in the CRM later is re-sent with the link`() = runTest {
        // Work-SIM call: BUSINESS at once, uploaded before the lookup answered.
        repos.setWorkSimSlot(1)
        repos.addCall(1, startedAt = recent, simSlot = 1,
            classification = Classification.BUSINESS, source = ClassificationSource.WORK_SIM)
        syncGate.reconcile(1)
        repos.markSynced(1)
        val before = repos.getActivity(1)!!
        repos.crmDirectory["+919876543210"] = ananya

        resolve()

        val after = repos.getActivity(1)!!
        assertEquals(ananya, repos.getCall(1)!!.crmMatch)
        assertEquals(SyncStatus.PENDING, after.syncStatus)
        assertEquals(before.clientId, after.clientId)
        assertTrue(after.updatedAt > before.updatedAt)
    }

    @Test
    fun `a renamed CRM record does not re-send an uploaded call`() = runTest {
        repos.crmDirectory["+919876543210"] = ananya
        professionalCall(1, startedAt = now - TimeUnit.HOURS.toMillis(30))
        resolve() // links (re-queued with the link)
        repos.markSynced(1)
        repos.crmDirectory["+919876543210"] = ananya.copy(name = "Ananya S.")

        repos.lookup("+919876543210", now)
        useCases.reclassifyNumber("+919876543210")

        assertEquals("Ananya S.", repos.getCall(1)!!.crmMatch?.name)
        assertEquals(SyncStatus.SYNCED, repos.getActivity(1)!!.syncStatus)
    }

    @Test
    fun `calls older than the lookup window are not looked up`() = runTest {
        professionalCall(1, startedAt = now - TimeUnit.DAYS.toMillis(8))

        resolve()

        assertTrue(repos.crmLookups.isEmpty())
    }

    @Test
    fun `CRM unreachable stops early, caches nothing and reports it`() = runTest {
        repos.crmAvailable = false
        professionalCall(1, number = "+919000000001")
        professionalCall(2, number = "+919000000002", startedAt = recent - 1)

        val result = resolve()

        assertTrue(result.unavailable)
        assertEquals(1, repos.crmLookups.size)
        assertTrue(repos.crmCache.isEmpty())
        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
    }

    @Test
    fun `a number removed from the CRM loses its link but stays professional`() = runTest {
        repos.crmCache["+919876543210"] = CachedCrmLookup(ananya, now - TimeUnit.HOURS.toMillis(30))
        professionalCall(1)
        repos.setCrmMatch("+919876543210", ananya)

        val result = resolve() // directory no longer has it

        assertFalse(result.unavailable)
        assertNull(repos.getCall(1)!!.crmMatch)
        assertEquals(Classification.BUSINESS, repos.getCall(1)!!.classification)
        assertTrue(1L in repos.outbox)
    }

    @Test
    fun `not in the CRM and saved in the phonebook - the lead is created with that name and linked`() = runTest {
        professionalCall(1, contactName = "Vikram Rao")

        val result = resolve()

        assertEquals(1, result.leadsCreated)
        val (lead, _) = repos.createLeadRequests.single()
        assertEquals("Vikram Rao", lead.name)
        assertEquals("+919876543210", lead.phone)
        val match = repos.getCall(1)!!.crmMatch!!
        assertEquals(CrmRecordType.LEAD, match.type)
        assertEquals("Vikram Rao", match.name)
    }

    @Test
    fun `no lead is created automatically with the switch off`() = runTest {
        repos.setAutoCreateLeads(false)
        professionalCall(1, contactName = "Vikram Rao")

        val result = resolve()

        assertEquals(0, result.leadsCreated)
        assertTrue(repos.createLeadRequests.isEmpty())
        assertNull(repos.getCall(1)!!.crmMatch)
    }

    @Test
    fun `a lead that can't be created offline is retried on the next run`() = runTest {
        professionalCall(1, contactName = "Vikram Rao")
        repos.createLeadResult = { _, _ -> CreateLeadResult.Unavailable }

        val first = resolve()

        assertTrue(first.unavailable)
        assertNull("the 'not found' is forgotten so the next run asks again", repos.crmCache["+919876543210"])
        repos.createLeadResult = null
        assertEquals(1, resolve().leadsCreated)
        assertEquals(CrmRecordType.LEAD, repos.getCall(1)!!.crmMatch?.type)
    }
}
