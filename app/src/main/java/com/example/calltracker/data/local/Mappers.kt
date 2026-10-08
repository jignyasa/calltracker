package com.example.calltracker.data.local

import com.example.calltracker.data.local.dao.ActivityWithSync
import com.example.calltracker.data.local.entity.CallEntity
import com.example.calltracker.data.local.entity.NumberRuleEntity
import com.example.calltracker.domain.model.Activity
import com.example.calltracker.domain.model.ActivityLog
import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.NumberRule

fun CallEntity.toDomain() = CallRecord(
    id = id,
    callLogId = callLogId,
    rawNumber = rawNumber,
    normalizedNumber = normalizedNumber,
    deviceContactName = deviceContactName,
    type = type,
    startedAt = startedAt,
    durationSec = durationSec,
    phoneAccountId = phoneAccountId,
    simSlot = simSlot,
    classification = classification,
    classificationSource = classificationSource,
    crmMatch = if (crmType != null && crmId != null) {
        CrmMatch(type = crmType, id = crmId, name = crmName.orEmpty(), company = crmCompany)
    } else {
        null
    },
)

fun ActivityWithSync.toDomain() = Activity(
    callId = activity.callId,
    clientId = activity.clientId,
    log = ActivityLog(
        notes = activity.notes,
        outcome = activity.outcome,
        followUpAt = activity.followUpAt,
        nextAction = activity.nextAction,
        remarks = activity.remarks,
    ),
    updatedAt = activity.updatedAt,
    serverId = activity.serverId,
    syncStatus = syncStatus,
    lastSyncError = syncError,
)

fun NumberRuleEntity.toDomain() = NumberRule(
    normalizedNumber = normalizedNumber,
    classification = classification,
    createdAt = createdAt,
)
