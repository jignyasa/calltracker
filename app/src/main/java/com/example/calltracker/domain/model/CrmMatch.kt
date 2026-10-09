package com.example.calltracker.domain.model

enum class CrmRecordType { CONTACT, LEAD }

data class CrmMatch(
    val type: CrmRecordType,
    val id: String,
    val name: String,
    val company: String?,
)

/** A stored CRM answer for a number. [match] null means the CRM confirmed it has no record. */
data class CachedCrmLookup(
    val match: CrmMatch?,
    val fetchedAt: Long,
)

sealed interface CrmLookupResult {
    data class Found(val match: CrmMatch) : CrmLookupResult

    /** The CRM has no record (or can't parse the number). Cached like a match. */
    data object NotFound : CrmLookupResult

    /** Network or server error; nothing cached, try again later. */
    data object Unavailable : CrmLookupResult
}

/** A lead to create in the CRM. [phone] is E.164. */
data class NewLead(
    val name: String,
    val phone: String,
    val company: String?,
)

sealed interface CreateLeadResult {
    data class Created(val lead: CrmMatch) : CreateLeadResult

    /** The number already belongs to a CRM record; [match] is that record. */
    data class AlreadyExists(val match: CrmMatch) : CreateLeadResult

    /** The CRM rejected the request (validation); retrying unchanged won't help. */
    data class Rejected(val reason: String) : CreateLeadResult

    data object Unavailable : CreateLeadResult
}
