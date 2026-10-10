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

/**
 * v3, data only: classification no longer comes from a CRM match or a saved phone contact; the number's saved answer
 * (a rule) decides. Calls those signals decided are kept as they are by turning them into saved answers: BUSINESS for
 * numbers found in the CRM (inserted first, so it wins), PERSONAL for saved contacts. A rule the user set is kept.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for ((source, classification) in listOf("CRM_MATCH" to "BUSINESS", "DEVICE_CONTACT" to "PERSONAL")) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO number_rules (normalized_number, classification, created_at)
                SELECT normalized_number, '$classification', MAX(updated_at) FROM calls
                WHERE classification_source = '$source' AND classification = '$classification'
                  AND normalized_number IS NOT NULL
                GROUP BY normalized_number
                """.trimIndent(),
            )
        }
        db.execSQL("UPDATE calls SET classification_source = 'USER_RULE' WHERE classification_source IN ('CRM_MATCH', 'DEVICE_CONTACT')")
    }
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
