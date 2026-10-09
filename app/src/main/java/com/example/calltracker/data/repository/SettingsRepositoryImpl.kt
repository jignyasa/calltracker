package com.example.calltracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.calltracker.di.IoDispatcher
import com.example.calltracker.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** App settings in SharedPreferences: nothing here is sensitive, and they must be readable before the DB opens. */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SettingsRepository {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun observeWorkSimSlot(): Flow<Int?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_WORK_SIM_SLOT) trySend(readWorkSimSlot())
        }
        trySend(readWorkSimSlot())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(io)

    override suspend fun workSimSlot(): Int? = withContext(io) { readWorkSimSlot() }

    override suspend fun setWorkSimSlot(slot: Int?) = withContext(io) {
        prefs.edit {
            if (slot == null) remove(KEY_WORK_SIM_SLOT) else putInt(KEY_WORK_SIM_SLOT, slot)
        }
    }

    override fun observeBatteryHintDismissed(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BATTERY_HINT_DISMISSED) trySend(prefs.getBoolean(KEY_BATTERY_HINT_DISMISSED, false))
        }
        trySend(prefs.getBoolean(KEY_BATTERY_HINT_DISMISSED, false))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(io)

    override suspend fun dismissBatteryHint() = withContext(io) {
        prefs.edit { putBoolean(KEY_BATTERY_HINT_DISMISSED, true) }
    }

    private fun readWorkSimSlot(): Int? =
        if (prefs.contains(KEY_WORK_SIM_SLOT)) prefs.getInt(KEY_WORK_SIM_SLOT, 0) else null

    private companion object {
        const val PREFS = "settings"
        const val KEY_WORK_SIM_SLOT = "work_sim_slot"
        const val KEY_BATTERY_HINT_DISMISSED = "battery_hint_dismissed"
    }
}
