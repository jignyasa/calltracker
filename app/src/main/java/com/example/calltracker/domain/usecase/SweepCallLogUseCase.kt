package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallLogEntry
import com.example.calltracker.domain.model.ClassificationSignals
import com.example.calltracker.domain.model.SweepWatermark
import com.example.calltracker.domain.phone.PhoneNumberNormalizer
import com.example.calltracker.domain.repository.CallLogAccessDeniedException
import com.example.calltracker.domain.repository.CallLogSource
import com.example.calltracker.domain.repository.CallRepository
import com.example.calltracker.domain.repository.NumberRuleRepository
import com.example.calltracker.domain.repository.SweepStateRepository
import com.example.calltracker.domain.repository.TransactionRunner
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Imports new CallLog rows. The only way calls enter the app: the PHONE_STATE receiver, boot, app open,
 * the CallLog content trigger and the periodic worker all just run this sweep.
 *
 * Each page is stored, classified, reconciled with the outbox and the watermark advanced in one transaction,
 * so a crash or cancellation mid-sweep never loses or double-imports a call.
 */
class SweepCallLogUseCase @Inject constructor(
    private val source: CallLogSource,
    private val calls: CallRepository,
    private val rules: NumberRuleRepository,
    private val sweepState: SweepStateRepository,
    private val normalizer: PhoneNumberNormalizer,
    private val classify: ClassifyCallUseCase,
    private val syncGate: SyncGate,
    private val transaction: TransactionRunner,
    private val clock: Clock,
) {

    sealed interface Result {
        data class Completed(val imported: List<Long>, val scanned: Int) : Result
        data object PermissionDenied : Result
    }

    suspend operator fun invoke(): Result {
        var watermark = sweepState.get() ?: SweepWatermark(
            // No id lower bound on the first run; only the backfill window applies.
            lastCallLogId = Long.MAX_VALUE,
            lastStartedAt = clock.millis() - BACKFILL_MS,
        )
        val queryWatermark = watermark
        var cursor = -1L
        var scanned = 0
        val imported = mutableListOf<Long>()

        while (true) {
            val page = try {
                source.read(queryWatermark, OVERLAP_MS, afterId = cursor, limit = PAGE_SIZE)
            } catch (_: CallLogAccessDeniedException) {
                return Result.PermissionDenied
            }
            if (page.isEmpty()) break

            watermark = advance(watermark, page)
            val pageWatermark = watermark
            transaction.run {
                val now = clock.millis()
                for (entry in page) {
                    val id = ingest(entry, now) ?: continue
                    syncGate.reconcile(id)
                    imported += id
                }
                sweepState.save(pageWatermark, now)
            }
            scanned += page.size
            cursor = page.last().callLogId
            if (page.size < PAGE_SIZE) break
        }

        if (scanned == 0) sweepState.save(watermark, clock.millis())
        return Result.Completed(imported, scanned)
    }

    private suspend fun ingest(entry: CallLogEntry, now: Long): Long? {
        val number = normalizer.normalize(entry.rawNumber)
        val signals = ClassificationSignals(
            userRule = number?.let { rules.getRule(it)?.classification },
            isDeviceContact = !entry.cachedName.isNullOrBlank(),
        )
        return calls.insertIfNew(entry, number, classify(signals), now)
    }

    private fun advance(current: SweepWatermark, page: List<CallLogEntry>): SweepWatermark {
        val maxId = page.maxOf { it.callLogId }
        return SweepWatermark(
            lastCallLogId = if (current.lastCallLogId == Long.MAX_VALUE) maxId else maxOf(current.lastCallLogId, maxId),
            lastStartedAt = maxOf(current.lastStartedAt, page.maxOf { it.startedAt }),
        )
    }

    companion object {
        val BACKFILL_MS = TimeUnit.DAYS.toMillis(7)

        /** Re-read rows this close to the newest seen start time (clock skew, rows committed late). */
        val OVERLAP_MS = TimeUnit.MINUTES.toMillis(2)
        const val PAGE_SIZE = 200
    }
}
