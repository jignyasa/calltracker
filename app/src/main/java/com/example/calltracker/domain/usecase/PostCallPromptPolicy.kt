package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallRecord
import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import java.util.concurrent.TimeUnit

/**
 * Whether to ask "work or personal?" after a call. Only for calls the rule engine couldn't decide, that just ended
 * (not the backfill or an old catch-up), and that are real conversations or attempts.
 */
object PostCallPromptPolicy {

    val PROMPT_WINDOW_MS = TimeUnit.HOURS.toMillis(1)

    /** Never more than this many prompts at once (e.g. after the phone was off); the rest wait in the app. */
    const val MAX_PROMPTS = 3

    private val promptableTypes = setOf(CallType.INCOMING, CallType.OUTGOING, CallType.MISSED, CallType.REJECTED)

    fun shouldPrompt(call: CallRecord, now: Long): Boolean =
        call.classification == Classification.UNCLASSIFIED &&
            call.type in promptableTypes &&
            call.endedAt >= now - PROMPT_WINDOW_MS
}
