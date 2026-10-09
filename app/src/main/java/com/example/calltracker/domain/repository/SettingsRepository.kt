package com.example.calltracker.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeWorkSimSlot(): Flow<Int?>

    /** 0-based SIM slot the user uses for work; calls on it default to BUSINESS. Null when not set. */
    suspend fun workSimSlot(): Int?

    suspend fun setWorkSimSlot(slot: Int?)

    /** The user said "Not now" to the background-reliability hint on the call list. */
    fun observeBatteryHintDismissed(): Flow<Boolean>

    suspend fun dismissBatteryHint()
}
