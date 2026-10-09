package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.CallType
import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.testing.FakeRepositories
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PostCallPromptPolicyTest {

    private val repos = FakeRepositories()
    private val call = repos.addCall(1) // OUTGOING, 60 s, UNCLASSIFIED
    private val justEnded = call.endedAt + TimeUnit.MINUTES.toMillis(1)

    @Test
    fun `an undecided call that just ended is prompted`() {
        assertTrue(PostCallPromptPolicy.shouldPrompt(call, justEnded))
    }

    @Test
    fun `decided, old or blocked calls are not`() {
        val business = call.copy(classification = Classification.BUSINESS, classificationSource = ClassificationSource.CRM_MATCH)
        assertFalse(PostCallPromptPolicy.shouldPrompt(business, justEnded))
        assertFalse(PostCallPromptPolicy.shouldPrompt(call, call.endedAt + TimeUnit.HOURS.toMillis(2)))
        assertFalse(PostCallPromptPolicy.shouldPrompt(call.copy(type = CallType.BLOCKED), justEnded))
    }
}
