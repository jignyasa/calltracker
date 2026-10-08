package com.example.calltracker.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calltracker.data.local.entity.CallEntity
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
        val outbox = OutboxRepositoryImpl(db.outboxDao())
        val rules = NumberRuleRepositoryImpl(db.numberRuleDao())
        val tx = RoomTransactionRunner(db)
        val gate = SyncGate(calls, activities, outbox, clock)
        val setClassification = SetCallClassificationUseCase(calls, activities, rules, gate, tx, clock)
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
