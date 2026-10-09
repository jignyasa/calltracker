package com.example.calltracker.domain.repository

import com.example.calltracker.domain.model.SimCard

interface SimCardSource {
    /** Active SIMs by slot; empty when READ_PHONE_STATE is missing or the device has no telephony. */
    suspend fun activeSims(): List<SimCard>
}
