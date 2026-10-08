package com.example.calltracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.calltracker.data.local.dao.ActivityDao
import com.example.calltracker.data.local.dao.CallDao
import com.example.calltracker.data.local.dao.NumberRuleDao
import com.example.calltracker.data.local.dao.OutboxDao
import com.example.calltracker.data.local.dao.SweepStateDao
import com.example.calltracker.data.local.entity.ActivityEntity
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.data.local.entity.CrmCacheEntity
import com.example.calltracker.data.local.entity.NumberRuleEntity
import com.example.calltracker.data.local.entity.OutboxEntity
import com.example.calltracker.data.local.entity.SweepStateEntity

@Database(
    entities = [
        CallEntity::class,
        ActivityEntity::class,
        OutboxEntity::class,
        NumberRuleEntity::class,
        CrmCacheEntity::class,
        SweepStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    abstract fun activityDao(): ActivityDao
    abstract fun outboxDao(): OutboxDao
    abstract fun numberRuleDao(): NumberRuleDao
    abstract fun sweepStateDao(): SweepStateDao

    companion object {
        const val NAME = "calltracker.db"
    }
}
