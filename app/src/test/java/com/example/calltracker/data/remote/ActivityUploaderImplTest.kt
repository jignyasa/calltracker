package com.example.calltracker.data.remote

import com.example.calltracker.di.NetworkModule
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.ActivityPayload
import com.example.calltracker.domain.model.CallOutcome
import com.example.calltracker.domain.model.ServerActivity
import com.example.calltracker.domain.model.UploadItemResult
import com.example.calltracker.domain.model.UploadItemStatus
import com.example.calltracker.domain.model.UploadResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Against the batch contract in mock-server/README.md. */
class ActivityUploaderImplTest {

    private val server = MockWebServer()
    private val json = NetworkModule.provideJson()
    private lateinit var uploader: ActivityUploaderImpl

    private val payload = ActivityPayload(
        clientId = "3f0b8f0e-6c2a-4b1e-9d6e-0c1f2a3b4c5d",
        phone = "+919876543210",
        callType = "INCOMING",
        startedAt = 1_790_000_000_000,
        endedAt = 1_790_000_060_000,
        durationSec = 60,
        simSlot = null,
        leadId = null,
        contactId = "con_00001",
        notes = "quote",
        outcome = CallOutcome.CALL_BACK,
        followUpAt = null,
        nextAction = null,
        remarks = null,
        updatedAt = 1_790_000_100_000,
    )

    @Before
    fun setUp() {
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CrmApi::class.java)
        uploader = ActivityUploaderImpl(api)
    }

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    @Test
    fun `sends the items and reads per-item results`() = runTest {
        respond(
            200,
            """{"results":[
                {"clientId":"${payload.clientId}","status":"CREATED","serverId":"act_00001","version":1},
                {"clientId":"other","status":"INVALID","errors":["phone is invalid"]},
                {"clientId":"x","status":"SOMETHING_NEW"}
            ]}""",
        )

        val result = uploader.upload(listOf(payload))

        val request = server.takeRequest()
        assertEquals("/api/v1/activities/batch", request.url.encodedPath)
        val item = Json.parseToJsonElement(request.body!!.utf8()).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals(payload.clientId, item["clientId"]!!.jsonPrimitive.content)
        assertEquals("CALL_BACK", item["outcome"]!!.jsonPrimitive.content)
        assertEquals("con_00001", item["contactId"]!!.jsonPrimitive.content)
        assertFalse("nulls are left out", "leadId" in item)

        assertEquals(
            UploadResult.Processed(
                listOf(
                    UploadItemResult(payload.clientId, UploadItemStatus.CREATED, "act_00001", 1),
                    UploadItemResult("other", UploadItemStatus.INVALID, null, null, errors = listOf("phone is invalid")),
                    // unknown status dropped → the use case retries that item
                ),
            ),
            result,
        )
    }

    @Test
    fun `conflict carries the server's copy`() = runTest {
        respond(
            200,
            """{"results":[{"clientId":"${payload.clientId}","status":"CONFLICT","serverId":"act_00001","version":2,
               "server":{"clientId":"${payload.clientId}","phone":"+919876543210","notes":"edited","outcome":"CONVERTED",
               "updatedAt":1790000200000,"serverId":"act_00001","version":2}}]}""",
        )

        val item = (uploader.upload(listOf(payload)) as UploadResult.Processed).items.single()

        assertEquals(UploadItemStatus.CONFLICT, item.status)
        assertEquals(ServerActivity(ActivityLog(notes = "edited", outcome = CallOutcome.CONVERTED), 1_790_000_200_000), item.server)
    }

    @Test
    fun `server errors and dropped connections fail the whole batch`() = runTest {
        respond(503, """{"error":"INJECTED_FAULT"}""")
        respond(429, """{"error":"INJECTED_FAULT"}""")
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        repeat(3) { assertTrue(uploader.upload(listOf(payload)) is UploadResult.Failed) }
    }
}
