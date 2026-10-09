package com.example.calltracker.data.repository

import android.util.Log
import com.example.calltracker.data.local.dao.CrmCacheDao
import com.example.calltracker.data.local.entity.CrmCacheEntity
import com.example.calltracker.data.remote.CrmApi
import com.example.calltracker.data.remote.CreateLeadRequest
import com.example.calltracker.data.remote.ErrorResponse
import com.example.calltracker.data.remote.toDomain
import com.example.calltracker.domain.model.CachedCrmLookup
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.NewLead
import com.example.calltracker.domain.repository.CrmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

class CrmRepositoryImpl @Inject constructor(
    private val api: CrmApi,
    private val cache: CrmCacheDao,
    private val json: Json,
) : CrmRepository {

    override suspend fun cached(normalizedNumber: String): CachedCrmLookup? =
        cache.get(normalizedNumber)?.let { entry ->
            CachedCrmLookup(match = entry.toMatch(), fetchedAt = entry.fetchedAt)
        }

    override suspend fun lookup(normalizedNumber: String, now: Long): CrmLookupResult {
        val response = call { api.lookup(normalizedNumber) } ?: return CrmLookupResult.Unavailable
        val result = when {
            response.isSuccessful -> response.body()?.match?.toDomain()
                ?.let { CrmLookupResult.Found(it) } ?: CrmLookupResult.NotFound
            // The CRM can't parse the number, so it can never match: a definite "no".
            response.code() == 400 -> CrmLookupResult.NotFound
            else -> {
                Log.w(TAG, "Lookup failed: HTTP ${response.code()}")
                return CrmLookupResult.Unavailable
            }
        }
        store(normalizedNumber, (result as? CrmLookupResult.Found)?.match, now)
        return result
    }

    override suspend fun createLead(lead: NewLead, idempotencyKey: String, now: Long): CreateLeadResult {
        val response = call {
            api.createLead(idempotencyKey, CreateLeadRequest(lead.name, lead.phone, lead.company))
        } ?: return CreateLeadResult.Unavailable

        val result = when (response.code()) {
            200, 201 -> response.body()?.lead?.toDomain()?.let { CreateLeadResult.Created(it) }
            409 -> response.error()?.match?.toDomain()?.let { CreateLeadResult.AlreadyExists(it) }
            400, 422 -> CreateLeadResult.Rejected(response.error()?.error ?: "HTTP ${response.code()}")
            else -> null
        }
        when (result) {
            is CreateLeadResult.Created -> store(lead.phone, result.lead, now)
            is CreateLeadResult.AlreadyExists -> store(lead.phone, result.match, now)
            else -> Unit
        }
        return result ?: CreateLeadResult.Unavailable.also { Log.w(TAG, "Create lead failed: HTTP ${response.code()}") }
    }

    /** Null on network failure or an unreadable body. Never logs the request (it carries a phone number). */
    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T>? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Log.w(TAG, "CRM unreachable: ${e.javaClass.simpleName}")
        null
    } catch (e: SerializationException) {
        Log.w(TAG, "Unreadable CRM response: ${e.javaClass.simpleName}")
        null
    }

    private fun Response<*>.error(): ErrorResponse? = try {
        errorBody()?.string()?.let { json.decodeFromString<ErrorResponse>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IOException) {
        null
    }

    private suspend fun store(number: String, match: CrmMatch?, now: Long) {
        cache.upsert(
            CrmCacheEntity(
                normalizedNumber = number,
                crmType = match?.type,
                crmId = match?.id,
                name = match?.name,
                company = match?.company,
                fetchedAt = now,
            ),
        )
    }

    private fun CrmCacheEntity.toMatch(): CrmMatch? =
        if (crmType != null && crmId != null) CrmMatch(crmType, crmId, name.orEmpty(), company) else null

    private companion object {
        const val TAG = "CrmRepository"
    }
}
