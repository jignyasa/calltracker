package com.example.calltracker.data.remote

import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import kotlinx.serialization.Serializable

/** Wire format of the CRM API; see mock-server/README.md. Unknown fields are ignored. */
@Serializable
data class CrmRecordDto(
    val type: String,
    val id: String,
    val name: String,
    val company: String? = null,
    val phone: String? = null,
)

@Serializable
data class LookupResponse(
    val phone: String,
    val match: CrmRecordDto? = null,
)

@Serializable
data class CreateLeadRequest(
    val name: String,
    val phone: String,
    val company: String? = null,
)

@Serializable
data class CreateLeadResponse(
    val lead: CrmRecordDto,
)

/** Body of every 4xx/5xx; `match` is set for 409 PHONE_EXISTS. */
@Serializable
data class ErrorResponse(
    val error: String,
    val match: CrmRecordDto? = null,
    val details: List<String> = emptyList(),
)

/** Null for a record type this app doesn't know (newer server); treated as no match. */
fun CrmRecordDto.toDomain(): CrmMatch? {
    val recordType = CrmRecordType.entries.firstOrNull { it.name == type } ?: return null
    return CrmMatch(type = recordType, id = id, name = name, company = company)
}
