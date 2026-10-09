package com.example.calltracker.data.repository

import com.example.calltracker.data.local.dao.CrmCacheDao
import com.example.calltracker.data.local.entity.CrmCacheEntity
import com.example.calltracker.data.remote.CrmApi
import com.example.calltracker.di.NetworkModule
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import com.example.calltracker.domain.model.NewLead
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Against the API contract in mock-server/README.md. */
class CrmRepositoryImplTest {

    private val server = MockWebServer()
    private val cache = FakeCrmCacheDao()
    private val json = NetworkModule.provideJson()
    private lateinit var repo: CrmRepositoryImpl
    private val now = 1_790_000_000_000L

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CrmApi::class.java)
        repo = CrmRepositoryImpl(api, cache, json)
    }

    @After
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    @Test
    fun `lookup match is returned and cached`() = runTest {
        respond(200, """{"phone":"+919876543210","match":{"type":"CONTACT","id":"con_00001","name":"Ananya Sharma","company":"Sharma Traders","phone":"+919876543210","createdAt":1}}""")

        val result = repo.lookup("+919876543210", now)

        val expected = CrmMatch(CrmRecordType.CONTACT, "con_00001", "Ananya Sharma", "Sharma Traders")
        assertEquals(CrmLookupResult.Found(expected), result)
        assertEquals(expected, repo.cached("+919876543210")?.match)
        assertEquals(now, repo.cached("+919876543210")?.fetchedAt)
        val request = server.takeRequest()
        assertEquals("/api/v1/contacts/lookup", request.url.encodedPath)
        assertEquals("+919876543210", request.url.queryParameter("phone"))
    }

    @Test
    fun `no match and unparseable number are cached as a definite no`() = runTest {
        respond(200, """{"phone":"+919123456789","match":null}""")
        respond(400, """{"error":"INVALID_PHONE"}""")

        assertEquals(CrmLookupResult.NotFound, repo.lookup("+919123456789", now))
        assertEquals(CrmLookupResult.NotFound, repo.lookup("+91123", now))
        assertNull(repo.cached("+919123456789")!!.match)
        assertNull(repo.cached("+91123")!!.match)
    }

    @Test
    fun `server errors, rate limits and dropped connections are unavailable and not cached`() = runTest {
        respond(503, """{"error":"INJECTED_FAULT"}""")
        respond(429, """{"error":"INJECTED_FAULT"}""")
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())

        repeat(3) { assertEquals(CrmLookupResult.Unavailable, repo.lookup("+919876543210", now)) }
        assertNull(repo.cached("+919876543210"))
    }

    @Test
    fun `create lead sends the idempotency key and caches the new lead`() = runTest {
        respond(201, """{"lead":{"type":"LEAD","id":"lead_00003","name":"Vikram Rao","company":"Rao Pharma","phone":"+919555512345","source":"CALL","createdAt":1}}""")

        val result = repo.createLead(NewLead("Vikram Rao", "+919555512345", "Rao Pharma"), "key-1", now)

        val lead = CrmMatch(CrmRecordType.LEAD, "lead_00003", "Vikram Rao", "Rao Pharma")
        assertEquals(CreateLeadResult.Created(lead), result)
        assertEquals(lead, repo.cached("+919555512345")?.match)
        val request = server.takeRequest()
        assertEquals("key-1", request.headers["Idempotency-Key"])
        val body = request.body!!.utf8()
        assertTrue(body.contains("\"name\":\"Vikram Rao\"") && body.contains("\"phone\":\"+919555512345\""))
    }

    @Test
    fun `existing phone returns the existing record`() = runTest {
        respond(409, """{"error":"PHONE_EXISTS","match":{"type":"CONTACT","id":"con_00001","name":"Ananya Sharma","company":null,"phone":"+919876543210"}}""")

        val result = repo.createLead(NewLead("X", "+919876543210", null), "key-2", now)

        val existing = CrmMatch(CrmRecordType.CONTACT, "con_00001", "Ananya Sharma", null)
        assertEquals(CreateLeadResult.AlreadyExists(existing), result)
        assertEquals(existing, repo.cached("+919876543210")?.match)
    }

    @Test
    fun `validation and key reuse are rejected, 5xx is unavailable`() = runTest {
        respond(400, """{"error":"VALIDATION_FAILED","details":["name is required"]}""")
        respond(422, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
        respond(500, """{"error":"INTERNAL"}""")
        val lead = NewLead("X", "+919555512345", null)

        assertEquals(CreateLeadResult.Rejected("VALIDATION_FAILED"), repo.createLead(lead, "k", now))
        assertEquals(CreateLeadResult.Rejected("IDEMPOTENCY_KEY_REUSED"), repo.createLead(lead, "k", now))
        assertEquals(CreateLeadResult.Unavailable, repo.createLead(lead, "k", now))
        assertNull(repo.cached("+919555512345"))
    }

    private class FakeCrmCacheDao : CrmCacheDao {
        val rows = mutableMapOf<String, CrmCacheEntity>()
        override suspend fun get(number: String) = rows[number]
        override suspend fun upsert(entry: CrmCacheEntity) {
            rows[entry.normalizedNumber] = entry
        }
    }
}
