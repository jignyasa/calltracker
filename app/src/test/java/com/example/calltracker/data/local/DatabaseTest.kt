package com.example.calltracker.data.local

import android.content.Context
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.data.local.entity.CrmCacheEntity
import com.example.calltracker.data.local.entity.NumberRuleEntity
import com.example.calltracker.data.repository.ActivityRepositoryImpl
import com.example.calltracker.data.repository.CallRepositoryImpl
import com.example.calltracker.data.repository.NumberRuleRepositoryImpl
import com.example.calltracker.data.repository.OutboxRepositoryImpl
import com.example.calltracker.data.repository.RoomTransactionRunner
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.SyncStatus
import com.example.calltracker.domain.repository.CallPromptDismisser
import com.example.calltracker.domain.repository.CrmLookupRequester
import com.example.calltracker.domain.repository.SyncRequester
import com.example.calltracker.domain.usecase.SaveActivityLogUseCase
import com.example.calltracker.domain.usecase.SetCallClassificationUseCase
import com.example.calltracker.domain.usecase.SyncGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** DAO behaviour plus the use cases wired to real Room repositories (unencrypted in-memory DB). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseTest {

    private lateinit var db: AppDatabase
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun call(callLogId: Long, number: String? = "+919876543210", classification: Classification = Classification.UNCLASSIFIED, source: ClassificationSource = ClassificationSource.NONE) =
        CallEntity(
            callLogId = callLogId,
            startedAt = 1_789_000_000_000 + callLogId,
            rawNumber = number,
            normalizedNumber = number,
            deviceContactName = null,
            type = CallType.INCOMING,
            durationSec = 30,
            phoneAccountId = null,
            simSlot = null,
            classification = classification,
            classificationSource = source,
            crmType = null,
            crmId = null,
            crmName = null,
            crmCompany = null,
            createdAt = 0,
            updatedAt = 0,
        )

    @Test
    fun numbersNeedingCrmLookupOnlyListsProfessionalNumbersWithoutAFreshAnswer() = runTest {
        val dao = db.callDao()
        val base = 1_789_000_000_000L
        fun pro(id: Long, number: String?) = call(id, number, Classification.BUSINESS, ClassificationSource.USER_RULE)
        dao.insertIgnore(pro(1, "+919000000001"))                     // never looked up → needed
        dao.insertIgnore(pro(2, "+919000000002"))                     // fresh answer → skip
        dao.insertIgnore(pro(3, "+919000000003"))                     // stale answer → needed
        dao.insertIgnore(pro(4, "+919000000004"))                     // personal rule → never
        dao.insertIgnore(call(5, "+919000000005", Classification.PERSONAL, ClassificationSource.USER_MANUAL))
        dao.insertIgnore(pro(6, "+919000000005"))                     // same number, other call → still never
        dao.insertIgnore(pro(7, null))                                // private → never
        dao.insertIgnore(pro(8, "+919000000001"))                     // duplicate number → listed once
        dao.insertIgnore(pro(9, "+919000000009"))                     // fresh answer, but from before this call ended → needed
        dao.insertIgnore(call(10, "+919000000010"))                   // unsorted: not professional → never sent
        // Calls last 30 s: the answer for +…02 came after its call ended, the one for +…09 during the call.
        db.crmCacheDao().upsert(CrmCacheEntity("+919000000002", null, null, null, null, fetchedAt = base + 60_000))
        db.crmCacheDao().upsert(CrmCacheEntity("+919000000009", null, null, null, null, fetchedAt = base + 10_000))
        db.crmCacheDao().upsert(CrmCacheEntity("+919000000003", null, null, null, null, fetchedAt = base - 10))
        db.numberRuleDao().upsert(NumberRuleEntity("+919000000004", Classification.PERSONAL, 0))

        val numbers = dao.numbersNeedingCrmLookup(since = base, staleBefore = base - 1, limit = 10)

        // Most recent call first: +…09 has call 9, +…01 has call 8.
        assertEquals(listOf("+919000000009", "+919000000001", "+919000000003"), numbers)
        assertEquals(emptyList<String>(), dao.numbersNeedingCrmLookup(since = base + 100, staleBefore = base - 1, limit = 10))
    }

    @Test
    fun pagedCallListFiltersInSqlAndCountsUploads() = runTest {
        val dao = db.callDao()
        val business = dao.insertIgnore(call(1, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH))
        val failed = dao.insertIgnore(call(2, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH))
        dao.insertIgnore(call(3, classification = Classification.PERSONAL, source = ClassificationSource.DEVICE_CONTACT))
        repeat(60) { dao.insertIgnore(call(100L + it)) } // unclassified backlog
        db.outboxDao().enqueue(business, "client-1", now = 1)
        db.outboxDao().enqueue(failed, "client-2", now = 1)
        db.outboxDao().markFailed(failed, generation = 1, status = SyncStatus.FAILED_PERMANENT, error = "x", now = 2)

        suspend fun page(classification: Classification?, statuses: Set<SyncStatus>?) =
            (TestPager(PagingConfig(pageSize = 40, initialLoadSize = 40, enablePlaceholders = false), dao.pagedList(classification, statuses == null, statuses.orEmpty().toList()))
                .refresh() as PagingSource.LoadResult.Page).data

        val first = page(null, null)
        assertEquals("first page only", 40, first.size)
        assertEquals("newest first", 159L, first.first().call.callLogId)
        assertEquals(listOf(failed, business), page(Classification.BUSINESS, null).map { it.call.id })
        assertEquals(listOf(failed), page(null, setOf(SyncStatus.FAILED_PERMANENT)).map { it.call.id })
        assertEquals(SyncStatus.PENDING, page(Classification.BUSINESS, setOf(SyncStatus.PENDING)).single().syncStatus)
        assertTrue(page(Classification.PERSONAL, setOf(SyncStatus.SYNCED)).isEmpty())

        val counts = dao.observeUploadCounts().first()
        assertEquals(1, counts.waiting)
        assertEquals(1, counts.failed)
    }

    @Test
    fun outboxClaimAndGenerationGuardedUpdates() = runTest {
        val dao = db.outboxDao()
        val a = db.callDao().insertIgnore(call(1))
        val b = db.callDao().insertIgnore(call(2))
        val c = db.callDao().insertIgnore(call(3))
        dao.enqueue(a, "client-a", now = 10)
        dao.enqueue(b, "client-b", now = 20)
        dao.enqueue(c, "client-c", now = 30)

        // Oldest first, limited, excluding what this run already tried.
        val claimed = dao.claim(limit = 2, exclude = listOf(a), now = 40)
        assertEquals(listOf(b, c), claimed.map { it.callId })
        assertEquals(SyncStatus.IN_FLIGHT, dao.get(b)!!.status)
        assertEquals(SyncStatus.PENDING, dao.get(a)!!.status)

        // The user edits b while it's being uploaded: the old generation's result must not mark it done.
        dao.enqueue(b, "client-b", now = 50)
        assertEquals(0, dao.markSynced(b, generation = 1, now = 60))
        assertEquals(SyncStatus.PENDING, dao.get(b)!!.status)
        assertEquals(1, dao.markSynced(b, generation = 2, now = 60))
        assertEquals(SyncStatus.SYNCED, dao.get(b)!!.status)

        assertEquals(1, dao.markFailed(c, generation = 1, status = SyncStatus.FAILED_RETRYABLE, error = "503", now = 70))
        assertEquals(1, dao.get(c)!!.attempts)
        assertEquals("503", dao.get(c)!!.lastError)

        // Crash recovery.
        dao.claim(limit = 10, exclude = emptyList(), now = 80)
        assertEquals(2, dao.resetInFlight(now = 90))
        assertEquals(SyncStatus.PENDING, dao.get(a)!!.status)
        assertEquals("synced rows are never re-claimed", SyncStatus.SYNCED, dao.get(b)!!.status)
    }

    @Test
    fun reinsertingTheSameCallLogRowIsIgnored() = runTest {
        val dao = db.callDao()
        val first = dao.insertIgnore(call(10))
        val again = dao.insertIgnore(call(10))

        assertTrue(first > 0)
        assertEquals(-1L, again)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun enqueueRearmsWithNewGenerationAndDeleteKeepsSyncedRows() = runTest {
        val callId = db.callDao().insertIgnore(call(1))
        val outbox = db.outboxDao()

        outbox.enqueue(callId, "uuid-1", now = 100)
        assertEquals(1, outbox.get(callId)!!.generation)

        outbox.enqueue(callId, "uuid-1", now = 200)
        val item = outbox.get(callId)!!
        assertEquals(2, item.generation)
        assertEquals(SyncStatus.PENDING, item.status)
        assertEquals(200L, item.enqueuedAt)

        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET status = 'SYNCED' WHERE call_id = $callId")
        assertEquals(0, outbox.deleteUnsynced(callId))
        assertEquals(SyncStatus.SYNCED, outbox.get(callId)!!.status)
    }

    @Test
    fun deletingACallCascadesToActivityAndOutbox() = runTest {
        val callId = db.callDao().insertIgnore(call(1, classification = Classification.BUSINESS))
        val activities = ActivityRepositoryImpl(db.activityDao())
        val activity = activities.ensureActivity(callId, now = 1)
        db.outboxDao().enqueue(callId, activity.clientId, now = 1)

        db.openHelper.writableDatabase.execSQL("DELETE FROM calls WHERE id = $callId")

        assertNull(db.activityDao().get(callId))
        assertNull(db.outboxDao().get(callId))
    }

    @Test
    fun useCasesAgainstRoomKeepClassificationAndOutboxInStep() = runTest {
        val calls = CallRepositoryImpl(db.callDao(), clock)
        val activities = ActivityRepositoryImpl(db.activityDao())
        val outbox = OutboxRepositoryImpl(db.outboxDao(), object : SyncRequester { override fun requestSync() = Unit })
        val rules = NumberRuleRepositoryImpl(db.numberRuleDao())
        val tx = RoomTransactionRunner(db)
        val gate = SyncGate(calls, activities, outbox, clock)
        val setClassification = SetCallClassificationUseCase(calls, activities, rules, gate, object : CrmLookupRequester { override fun requestLookup() = Unit }, object : CallPromptDismisser { override fun dismiss(callIds: Set<Long>) = Unit }, tx, clock)
        val save = SaveActivityLogUseCase(calls, activities, gate, tx, clock)

        val a = db.callDao().insertIgnore(call(1))
        val b = db.callDao().insertIgnore(call(2))
        val manual = db.callDao().insertIgnore(call(3, classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL))

        setClassification(a, Classification.BUSINESS, applyToNumber = true)

        assertEquals(Classification.BUSINESS, calls.getCall(b)!!.classification)
        assertEquals(Classification.PERSONAL, calls.getCall(manual)!!.classification)
        assertEquals(SyncStatus.PENDING, activities.getActivity(a)!!.syncStatus)
        assertEquals(SyncStatus.PENDING, activities.getActivity(b)!!.syncStatus)
        assertNull(db.outboxDao().get(manual))

        assertEquals(SaveActivityLogUseCase.Result.Saved, save(a, ActivityLog(notes = "Quote sent", outcome = CallOutcome.CALL_BACK)))
        val saved = activities.observeActivity(a).first()!!
        assertEquals("Quote sent", saved.log.notes)
        assertEquals(CallOutcome.CALL_BACK, saved.log.outcome)
        assertEquals(2, db.outboxDao().get(a)!!.generation)

        setClassification(a, Classification.PERSONAL, applyToNumber = true)
        assertNull(db.outboxDao().get(a))
        assertNull(db.outboxDao().get(b))
        assertEquals(Classification.PERSONAL, rules.getRule("+919876543210")!!.classification)
    }
}
