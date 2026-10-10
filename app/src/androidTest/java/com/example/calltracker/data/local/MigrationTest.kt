package com.example.calltracker.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.calltracker.domain.usecase.SweepCallLogUseCase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2SetsTheBackfillFloorFromTheLastSweep() {
        val lastSweep = 1_790_000_000_000L
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO sweep_state (id, last_call_log_id, last_started_at, last_sweep_at) VALUES (0, 42, 1, $lastSweep)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, *ALL_MIGRATIONS)

        db.query("SELECT last_call_log_id, not_before FROM sweep_state").use {
            it.moveToFirst()
            assertEquals(42L, it.getLong(0))
            assertEquals(lastSweep - SweepCallLogUseCase.BACKFILL_MS, it.getLong(1))
        }
        db.close()
    }

    @Test
    fun migrate2To3TurnsOldAutomaticDecisionsIntoSavedAnswers() {
        helper.createDatabase(name, 2).apply {
            fun call(id: Int, number: String, classification: String, source: String) = execSQL(
                "INSERT INTO calls (id, call_log_id, started_at, normalized_number, type, duration_sec, classification, " +
                    "classification_source, created_at, updated_at) " +
                    "VALUES ($id, $id, 1000, '$number', 'OUTGOING', 60, '$classification', '$source', 1000, 2000)",
            )
            call(1, "+919000000001", "BUSINESS", "CRM_MATCH")
            call(2, "+919000000002", "PERSONAL", "DEVICE_CONTACT")
            call(3, "+919000000003", "UNCLASSIFIED", "NONE")
            call(4, "+919000000004", "PERSONAL", "DEVICE_CONTACT")
            execSQL("INSERT INTO number_rules (normalized_number, classification, created_at) VALUES ('+919000000004', 'BUSINESS', 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 3, true, *ALL_MIGRATIONS)

        val rules = db.query("SELECT normalized_number, classification FROM number_rules ORDER BY normalized_number").use {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
        }
        assertEquals(
            listOf(
                "+919000000001" to "BUSINESS",
                "+919000000002" to "PERSONAL",
                "+919000000004" to "BUSINESS", // the user's own rule is kept
            ),
            rules,
        )
        val calls = db.query("SELECT classification, classification_source FROM calls ORDER BY id").use {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
        }
        assertEquals(
            listOf("BUSINESS" to "USER_RULE", "PERSONAL" to "USER_RULE", "UNCLASSIFIED" to "NONE", "PERSONAL" to "USER_RULE"),
            calls,
        )
        db.close()
    }
}
