package com.example.calltracker.data.local

import android.content.Context
import androidx.room.Room
import com.example.calltracker.data.local.security.DatabaseKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object AppDatabaseFactory {

    private val libraryLoaded by lazy { System.loadLibrary("sqlcipher") }

    /** Builds the SQLCipher-encrypted database. */
    fun create(context: Context, keyProvider: DatabaseKeyProvider, name: String = AppDatabase.NAME): AppDatabase {
        libraryLoaded
        val key = keyProvider.getOrCreate()
        if (key is DatabaseKeyProvider.Key.Created) {
            // A database encrypted with a lost key can't be opened; start over. Calls are re-read from the CallLog.
            context.deleteDatabase(name)
        }
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.passphrase))
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
}
