package com.example.calltracker.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.calltracker.domain.usecase.SweepCallLogUseCase

/**
 * v2: `sweep_state.not_before`, the backfill floor applied on every sweep. Existing installs don't know when their
 * first sweep ran, so the floor is the backfill window before the last sweep: real late-committed rows are minutes
 * old, while anything older than that is a restored backup the first sweep would have skipped too.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sweep_state ADD COLUMN not_before INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE sweep_state SET not_before = last_sweep_at - ${SweepCallLogUseCase.BACKFILL_MS}")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
