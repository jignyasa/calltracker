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
}
