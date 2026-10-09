package com.example.calltracker.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/** The CRM REST API (`/api/v1`, bearer auth added by an interceptor). Contract: mock-server/README.md. */
interface CrmApi {

    @GET("api/v1/contacts/lookup")
    suspend fun lookup(@Query("phone") phone: String): Response<LookupResponse>

    @POST("api/v1/leads")
    suspend fun createLead(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateLeadRequest,
    ): Response<CreateLeadResponse>

    /** Each item's `clientId` is its idempotency key; the server answers per item, always HTTP 200. */
    @POST("api/v1/activities/batch")
    suspend fun uploadActivities(@Body request: ActivityBatchRequest): Response<ActivityBatchResponse>
}
