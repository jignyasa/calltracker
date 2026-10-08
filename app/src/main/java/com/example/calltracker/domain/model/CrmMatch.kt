package com.example.calltracker.domain.model

enum class CrmRecordType { CONTACT, LEAD }

data class CrmMatch(
    val type: CrmRecordType,
    val id: String,
    val name: String,
    val company: String?,
)
