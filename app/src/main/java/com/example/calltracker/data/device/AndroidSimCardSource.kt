package com.example.calltracker.data.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.calltracker.di.IoDispatcher
import com.example.calltracker.domain.model.SimCard
import com.example.calltracker.domain.repository.SimCardSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AndroidSimCardSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SimCardSource {

    override suspend fun activeSims(): List<SimCard> = withContext(io) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(SubscriptionManager::class.java)
        if (!granted || manager == null) return@withContext emptyList()
        try {
            manager.activeSubscriptionInfoList.orEmpty()
                .map { info ->
                    val name = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    SimCard(slot = info.simSlotIndex, label = name.orEmpty())
                }
                .sortedBy { it.slot }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
