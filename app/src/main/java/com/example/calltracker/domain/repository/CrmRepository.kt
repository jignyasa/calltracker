package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.CachedCrmLookup
import com.example.calltracker.domain.model.CreateLeadResult
import com.example.calltracker.domain.model.CrmLookupResult
import com.example.calltracker.domain.model.NewLead

/**
 * CRM records, cache first. Only E.164 numbers (and, for a new lead, the name and company the user typed)
 * are ever sent; no call details.
 */
interface CrmRepository {
    /** The last stored answer for [normalizedNumber], however old. No network. */
    suspend fun cached(normalizedNumber: String): CachedCrmLookup?

    /** Drops the cached answer, e.g. after the CRM said the linked record no longer exists; the next lookup re-asks. */
    suspend fun forget(normalizedNumber: String)

    /** Asks the CRM and caches a definite answer (match or no match). */
    suspend fun lookup(normalizedNumber: String, now: Long): CrmLookupResult

    /**
     * Creates a lead. [idempotencyKey] makes a retried request return the original lead instead of a duplicate.
     * A created or already-existing record is cached for its number.
     */
    suspend fun createLead(lead: NewLead, idempotencyKey: String, now: Long): CreateLeadResult
}

/** Asks for a CRM lookup run soon, for the numbers whose answer is missing or stale. */
interface CrmLookupRequester {
    fun requestLookup()
}
