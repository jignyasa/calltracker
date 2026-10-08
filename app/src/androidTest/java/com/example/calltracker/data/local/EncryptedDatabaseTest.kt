package com.example.calltracker.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.data.local.security.DatabaseKeyProvider
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "encryption-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun databaseFileIsNotPlaintextAndReopensWithTheStoredKey() = runTest {
        val keyProvider = DatabaseKeyProvider(context)
        val marker = "+919999988888"

        AppDatabaseFactory.create(context, keyProvider, name).apply {
            callDao().insertIgnore(sampleCall(marker))
            close()
        }

        val bytes = context.getDatabasePath(name).readBytes()
        val header = String(bytes.copyOfRange(0, 15), Charsets.US_ASCII)
        assertFalse("SQLite header found, file is plaintext", header == "SQLite format 3")
        assertFalse("Phone number visible in file", String(bytes, Charsets.ISO_8859_1).contains(marker))

        // A second open (new provider instance, same stored key) must read the data back.
        val reopened = AppDatabaseFactory.create(context, DatabaseKeyProvider(context), name)
        assertEquals(marker, reopened.callDao().observeAll().first().single().normalizedNumber)
        reopened.close()
    }

    private fun sampleCall(number: String) = CallEntity(
        callLogId = 1,
        startedAt = 1_790_000_000_000,
        rawNumber = number,
        normalizedNumber = number,
        deviceContactName = null,
        type = CallType.OUTGOING,
        durationSec = 42,
        phoneAccountId = null,
        simSlot = null,
        classification = Classification.BUSINESS,
        classificationSource = ClassificationSource.CRM_MATCH,
        crmType = null,
        crmId = null,
        crmName = null,
        crmCompany = null,
        createdAt = 0,
        updatedAt = 0,
    )
}
