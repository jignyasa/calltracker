package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import java.util.concurrent.TimeUnit

/**
 * Whether to ask "work or personal?" after a call. Only for calls the rule engine couldn't decide, that just ended
 * (not the backfill or an old catch-up), and that were conversations: a missed or rejected call from an unknown
 * number has nothing to log, so it waits under Unsorted instead of interrupting (a customer's missed call is still
 * matched in the CRM and logged without asking).
 */
object PostCallPromptPolicy {

    val PROMPT_WINDOW_MS = TimeUnit.HOURS.toMillis(1)

    /** Never more than this many prompts showing at once (e.g. after the phone was off); the rest wait in the app. */
    const val MAX_PROMPTS = 3

    private val promptableTypes = setOf(CallType.INCOMING, CallType.OUTGOING)

    fun shouldPrompt(call: CallRecord, now: Long): Boolean =
        call.classification == Classification.UNCLASSIFIED &&
            call.type in promptableTypes &&
            call.endedAt >= now - PROMPT_WINDOW_MS

    /** The log form only pops up right after the call; later the call simply waits in the list. */
    val LOG_OFFER_WINDOW_MS = TimeUnit.MINUTES.toMillis(10)

    /** A conversation (or attempt) the user just had; missed/rejected calls have nothing to write down yet. */
    private val loggableTypes = setOf(CallType.INCOMING, CallType.OUTGOING)

    /**
     * Whether to offer the log form (pop-up, or a notification without the overlay permission) after a call:
     * a BUSINESS call that just ended. Of several, only the newest is offered.
     */
    fun shouldOfferLogging(call: CallRecord, now: Long): Boolean =
        call.classification == Classification.BUSINESS &&
            call.type in loggableTypes &&
            call.endedAt >= now - LOG_OFFER_WINDOW_MS
}
