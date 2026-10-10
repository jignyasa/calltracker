package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.ServerActivity
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.model.UploadItemResult
import com.example.calltracker.domain.model.UploadItemStatus
import com.example.calltracker.domain.model.UploadResult
import com.example.calltracker.domain.repository.ActivityUploader
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

class UploadPendingActivitiesUseCaseTest {

    private val now = 1_790_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repos = FakeRepositories()
    private val useCases = TestUseCases(repos, clock)

    /** Scripted CRM: answers each item by its phone number's rule, CREATED by default. */
    private inner class FakeUploader : ActivityUploader {
        val batches = mutableListOf<List<ActivityPayload>>()
        var failWith: String? = null
        var statusFor: (ActivityPayload) -> UploadItemStatus = { UploadItemStatus.CREATED }
        var serverCopy: ServerActivity? = null
        var duringUpload: suspend () -> Unit = {}
        var dropResults = false

        override suspend fun upload(items: List<ActivityPayload>): UploadResult {
            batches += items
            duringUpload()
            failWith?.let { return UploadResult.Failed(it) }
            if (dropResults) return UploadResult.Processed(emptyList())
            return UploadResult.Processed(
                items.map {
                    val status = statusFor(it)
                    UploadItemResult(
                        clientId = it.clientId.uppercase(), // server may echo a different case
                        status = status,
                        serverId = if (status == UploadItemStatus.INVALID || status == UploadItemStatus.ERROR) null else "act_${it.phone.takeLast(2)}",
                        version = 1,
                        errors = if (status == UploadItemStatus.INVALID) listOf("leadId does not exist") else emptyList(),
                        server = serverCopy.takeIf { status == UploadItemStatus.CONFLICT },
                    )
                },
            )
        }

        val sentNumbers get() = batches.flatten().map { it.phone }
    }

    private val uploader = FakeUploader()
    private val upload = useCases.upload(uploader)

    private suspend fun businessCall(id: Long, number: String? = "+9190000000${id.toString().padStart(2, '0')}") {
        repos.addCall(id, number = number, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        useCases.syncGate.reconcile(id)
    }

    @Test
    fun `uploads a business call with exactly the contract fields`() = runTest {
        repos.addCall(1, number = "+919000000001", classification = Classification.BUSINESS,
            source = ClassificationSource.USER_MANUAL, deviceContactName = "Mom's friend", simSlot = 1)
        useCases.syncGate.reconcile(1)
        repos.setCrmMatch("+919000000001", CrmMatch(CrmRecordType.LEAD, "lead_00001", "Vikram", null))
        useCases.saveLog(1, ActivityLog(notes = "quote", outcome = CallOutcome.CALL_BACK, followUpAt = now + 86_400_000))

        val result = upload()

        val sent = uploader.batches.single().single()
        val activity = repos.getActivity(1)!!
        assertEquals(activity.clientId, sent.clientId)
        assertEquals("+919000000001", sent.phone)
        assertEquals("OUTGOING", sent.callType)
        assertEquals(1, sent.simSlot)
        assertEquals("lead_00001", sent.leadId)
        assertNull(sent.contactId)
        assertEquals("quote", sent.notes)
        assertEquals(CallOutcome.CALL_BACK, sent.outcome)
        assertEquals(activity.updatedAt, sent.updatedAt)
        assertEquals(sent.startedAt + 60_000, sent.endedAt)
        assertFalse("contact names never leave the phone", sent.toString().contains("Mom's friend"))

        assertEquals(UploadPendingActivitiesUseCase.Result(uploaded = 1, failedPermanently = 0, retryNeeded = false), result)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
        assertEquals("act_01", repos.getActivity(1)!!.serverId)
    }

    @Test
    fun `a personal call is never sent, even if it reached the outbox`() = runTest {
        businessCall(1)
        useCases.setClassification(1, Classification.PERSONAL, applyToNumber = false) // dequeued by SyncGate
        repos.addCall(2, number = "+919000000002") // UNCLASSIFIED
        repos.ensureActivity(2, now)
        repos.enqueue(2, repos.getActivity(2)!!.clientId, now) // bypassing SyncGate: the uploader must still refuse

        val result = upload()

        assertTrue(uploader.batches.isEmpty())
        assertEquals(0, result.uploaded)
        assertFalse(2L in repos.outbox)
    }

    @Test
    fun `one bad item never blocks the batch`() = runTest {
        businessCall(1)
        businessCall(2)
        businessCall(3)
        uploader.statusFor = {
            when (it.phone) {
                "+919000000002" -> UploadItemStatus.INVALID
                "+919000000003" -> UploadItemStatus.ERROR
                else -> UploadItemStatus.CREATED
            }
        }

        val result = upload()

        assertEquals(UploadPendingActivitiesUseCase.Result(uploaded = 1, failedPermanently = 1, retryNeeded = true), result)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
        assertEquals(SyncStatus.FAILED_PERMANENT, repos.outbox.getValue(2).status)
        assertEquals("leadId does not exist", repos.getActivity(2)!!.lastSyncError)
        assertEquals(SyncStatus.FAILED_RETRYABLE, repos.outbox.getValue(3).status)
        assertEquals(1, uploader.batches.size)

        // Next run: only the transient one goes again; the permanent failure waits for the user.
        uploader.statusFor = { UploadItemStatus.CREATED }
        upload()
        assertEquals(listOf("+919000000003"), uploader.batches[1].map { it.phone })
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(3).status)
        assertEquals(SyncStatus.FAILED_PERMANENT, repos.outbox.getValue(2).status)
    }

    @Test
    fun `network or server failure keeps every item for a retry`() = runTest {
        businessCall(1)
        businessCall(2)
        uploader.failWith = "CRM error HTTP 503"

        val result = upload()

        assertTrue(result.retryNeeded)
        for (id in listOf(1L, 2L)) {
            val row = repos.outbox.getValue(id)
            assertEquals(SyncStatus.FAILED_RETRYABLE, row.status)
            assertEquals(1, row.attempts)
            assertEquals("CRM error HTTP 503", row.lastError)
        }

        uploader.failWith = null
        assertFalse(upload().retryNeeded)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(2).status)
    }

    @Test
    fun `a replayed item the server already has is a duplicate and counts as uploaded`() = runTest {
        businessCall(1)
        uploader.failWith = null
        upload()
        // Same content re-sent (e.g. the response was lost before the item was marked): server says DUPLICATE.
        useCases.retryUpload(1)
        uploader.statusFor = { UploadItemStatus.DUPLICATE }

        val result = upload()

        assertEquals(1, result.uploaded)
        assertEquals(uploader.batches[0].single().clientId, uploader.batches[1].single().clientId)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `an edit made while uploading is not lost`() = runTest {
        businessCall(1)
        uploader.duringUpload = {
            uploader.duringUpload = {}
            useCases.saveLog(1, ActivityLog(notes = "typed during upload"))
        }

        upload()

        assertEquals("first send didn't count", SyncStatus.PENDING, repos.outbox.getValue(1).status)
        assertEquals(2, repos.outbox.getValue(1).generation)
        upload()
        assertEquals("typed during upload", uploader.batches[1].single().notes)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `conflict - the server's newer copy replaces the local log`() = runTest {
        businessCall(1)
        useCases.saveLog(1, ActivityLog(notes = "old local"))
        uploader.statusFor = { UploadItemStatus.CONFLICT }
        uploader.serverCopy = ServerActivity(ActivityLog(notes = "edited in the CRM", outcome = CallOutcome.CONVERTED), now + 5_000)

        val result = upload()

        val activity = repos.getActivity(1)!!
        assertEquals(1, result.uploaded)
        assertEquals("edited in the CRM", activity.log.notes)
        assertEquals(CallOutcome.CONVERTED, activity.log.outcome)
        assertEquals(now + 5_000, activity.updatedAt)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `after a conflict with a clock that runs ahead, the user's next edit still wins`() = runTest {
        businessCall(1)
        val ahead = now + 3_600_000 // CRM copy stamped an hour in our future
        uploader.statusFor = { UploadItemStatus.CONFLICT }
        uploader.serverCopy = ServerActivity(ActivityLog(notes = "from the CRM"), ahead)
        upload()
        assertEquals("from the CRM", repos.getActivity(1)!!.log.notes)

        useCases.saveLog(1, ActivityLog(notes = "from the CRM - my follow-up"))
        uploader.statusFor = { if (it.updatedAt > ahead) UploadItemStatus.UPDATED else UploadItemStatus.CONFLICT }
        upload()

        val sent = uploader.batches.last().single()
        assertTrue("edit stamped after the version it was based on", sent.updatedAt > ahead)
        assertEquals("from the CRM - my follow-up", repos.getActivity(1)!!.log.notes)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `items left in flight by a crash are sent again`() = runTest {
        businessCall(1)
        repos.claimBatch(10, emptySet(), now) // a run claimed it, then the process died
        assertEquals(SyncStatus.IN_FLIGHT, repos.outbox.getValue(1).status)

        upload()

        assertEquals(1, uploader.batches.size)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `a business call without a number fails locally and is never sent`() = runTest {
        businessCall(1, number = null)

        val result = upload()

        assertTrue(uploader.batches.isEmpty())
        assertEquals(1, result.failedPermanently)
        assertEquals(SyncStatus.FAILED_PERMANENT, repos.outbox.getValue(1).status)
    }

    @Test
    fun `a large backlog goes out in batches of at most 100`() = runTest {
        repeat(250) { businessCall(it + 1L, number = "+9190000${(10_000 + it).toString().padStart(5, '0')}") }

        val result = upload()

        assertEquals(listOf(100, 100, 50), uploader.batches.map { it.size })
        assertEquals(250, result.uploaded)
        assertEquals(250, uploader.sentNumbers.toSet().size)
    }

    @Test
    fun `an item missing from the response is retried, not marked done`() = runTest {
        businessCall(1)
        uploader.dropResults = true

        assertTrue(upload().retryNeeded)
        assertEquals(SyncStatus.FAILED_RETRYABLE, repos.outbox.getValue(1).status)
    }

    @Test
    fun `call types the CRM doesn't know are mapped to the nearest one`() {
        assertEquals("REJECTED", UploadPendingActivitiesUseCase.crmCallType(CallType.BLOCKED))
        assertEquals("MISSED", UploadPendingActivitiesUseCase.crmCallType(CallType.VOICEMAIL))
        assertEquals("INCOMING", UploadPendingActivitiesUseCase.crmCallType(CallType.OTHER))
    }

    @Test
    fun `stale CRM link - a call marked business by hand is re-sent without the link`() = runTest {
        repos.addCall(1, number = "+919000000001", classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        useCases.syncGate.reconcile(1)
        val gone = CrmMatch(CrmRecordType.LEAD, "lead_00099", "Deleted lead", null)
        repos.setCrmMatch("+919000000001", gone)
        repos.crmCache["+919000000001"] = com.example.calltracker.domain.model.CachedCrmLookup(gone, now)
        uploader.statusFor = { if (it.leadId != null) UploadItemStatus.INVALID else UploadItemStatus.CREATED }

        val first = upload()

        assertTrue("healed, retried on the next run", first.retryNeeded)
        assertEquals(0, first.failedPermanently)
        assertNull(repos.getCall(1)!!.crmMatch)
        assertNull("cache forgotten so the next lookup re-asks", repos.crmCache["+919000000001"])
        assertEquals(SyncStatus.PENDING, repos.outbox.getValue(1).status)

        upload()
        assertNull(uploader.batches[1].single().leadId)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `stale CRM link - a lookup is requested at once, and the record's new id re-links the call`() = runTest {
        repos.addCall(1, number = "+919000000001", classification = Classification.BUSINESS, source = ClassificationSource.USER_MANUAL)
        useCases.syncGate.reconcile(1)
        val gone = CrmMatch(CrmRecordType.CONTACT, "con_00099", "Moved", null)
        repos.setCrmMatch("+919000000001", gone)
        repos.crmCache["+919000000001"] = com.example.calltracker.domain.model.CachedCrmLookup(gone, now)
        uploader.statusFor = { if (it.contactId == "con_00099") UploadItemStatus.INVALID else UploadItemStatus.CREATED }

        upload()

        assertEquals("no waiting for the next sweep", 1, repos.crmLookupRequests)
        // The requested lookup finds the same person, now a lead.
        repos.crmDirectory["+919000000001"] = CrmMatch(CrmRecordType.LEAD, "lead_00042", "Moved", null)
        useCases.resolveCrm()
        upload()
        assertEquals("lead_00042", uploader.batches.last().single().leadId)
        assertEquals(SyncStatus.SYNCED, repos.outbox.getValue(1).status)
    }

    @Test
    fun `stale CRM link - a call that was business only through the CRM (data from before v3) drops out and is never sent`() = runTest {
        val gone = CrmMatch(CrmRecordType.CONTACT, "con_00099", "Deleted contact", null)
        repos.crmCache["+919000000001"] = com.example.calltracker.domain.model.CachedCrmLookup(gone, now)
        repos.addCall(1, number = "+919000000001", classification = Classification.BUSINESS,
            source = ClassificationSource.CRM_MATCH, deviceContactName = "Saved as contact")
        repos.setCrmMatch("+919000000001", gone)
        useCases.syncGate.reconcile(1)
        uploader.statusFor = { UploadItemStatus.INVALID } // fake answers "leadId does not exist"; any stale link counts

        upload()

        assertEquals("no saved answer: back to Unsorted, asked", Classification.UNCLASSIFIED, repos.getCall(1)!!.classification)
        assertFalse(1L in repos.outbox)
        upload()
        assertEquals("never sent again", 1, uploader.batches.size)
    }

    @Test
    fun `retry upload re-queues a rejected business call`() = runTest {
        businessCall(1)
        uploader.statusFor = { UploadItemStatus.INVALID }
        upload()
        assertEquals(SyncStatus.FAILED_PERMANENT, repos.outbox.getValue(1).status)

        assertTrue(useCases.retryUpload(1))

        assertEquals(SyncStatus.PENDING, repos.outbox.getValue(1).status)
        assertTrue(repos.syncRequests > 0)
    }
}
