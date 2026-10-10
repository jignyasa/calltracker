package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.testing.FakeRepositories
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SetCallClassificationUseCaseTest {

    private val repos = FakeRepositories()
    private val clock = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC)
    private val gate = SyncGate(repos, repos, repos, clock)
    private val setClassification = SetCallClassificationUseCase(repos, repos, repos, gate, repos, repos, repos, clock)

    @Test
    fun `marking business queues only that call when not applied to the number`() = runTest {
        repos.addCall(1)
        repos.addCall(2)

        val result = setClassification(1, Classification.BUSINESS, applyToNumber = false)

        assertEquals(SetCallClassificationUseCase.Result.Success(setOf(1L), alreadyUploaded = false), result)
        assertEquals(setOf(1L), repos.outbox.keys)
        assertNull(repos.getRule("+919876543210"))
        assertEquals(ClassificationSource.USER_MANUAL, repos.getCall(1)!!.classificationSource)
    }

    @Test
    fun `applying to the number saves a rule and reclassifies other calls except manual ones`() = runTest {
        repos.addCall(1)
        repos.addCall(2)
        repos.addCall(3, classification = Classification.PERSONAL, source = ClassificationSource.USER_MANUAL)
        repos.addCall(4, number = "+919000000000")

        val result = setClassification(1, Classification.BUSINESS, applyToNumber = true)

        assertEquals(setOf(1L, 2L), (result as SetCallClassificationUseCase.Result.Success).updatedCallIds)
        assertEquals(Classification.BUSINESS, repos.getRule("+919876543210")!!.classification)
        assertEquals(ClassificationSource.USER_RULE, repos.getCall(2)!!.classificationSource)
        assertEquals(Classification.PERSONAL, repos.getCall(3)!!.classification)
        assertEquals(Classification.UNCLASSIFIED, repos.getCall(4)!!.classification)
        assertEquals(setOf(1L, 2L), repos.outbox.keys)
    }

    @Test
    fun `marking personal removes pending items for every affected call`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        repos.addCall(2, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        gate.reconcile(1)
        gate.reconcile(2)

        setClassification(1, Classification.PERSONAL, applyToNumber = true)

        assertTrue(repos.outbox.isEmpty())
    }

    @Test
    fun `marking an already uploaded call personal reports it`() = runTest {
        repos.addCall(1, classification = Classification.BUSINESS, source = ClassificationSource.CRM_MATCH)
        gate.reconcile(1)
        repos.markSynced(1)

        val result = setClassification(1, Classification.PERSONAL, applyToNumber = false)

        assertTrue((result as SetCallClassificationUseCase.Result.Success).alreadyUploaded)
    }

    @Test
    fun `private numbers can be classified but never create a rule`() = runTest {
        repos.addCall(1, number = null)

        val result = setClassification(1, Classification.BUSINESS, applyToNumber = true)

        assertEquals(setOf(1L), (result as SetCallClassificationUseCase.Result.Success).updatedCallIds)
        assertFalse(repos.outbox.isEmpty())
    }

    @Test
    fun `unknown call returns CallNotFound`() = runTest {
        assertEquals(
            SetCallClassificationUseCase.Result.CallNotFound,
            setClassification(9, Classification.BUSINESS, applyToNumber = false),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `user cannot choose UNCLASSIFIED`() = runTest {
        repos.addCall(1)
        setClassification(1, Classification.UNCLASSIFIED, applyToNumber = false)
    }

    @Test
    fun `an answer saved for the number means its next call is decided without asking`() = runTest {
        repos.addCall(1)
        setClassification(1, Classification.PERSONAL, applyToNumber = true)

        // The next call from the number, through the same rule engine the sweep uses.
        val next = repos.addCall(2)
        val decision = ClassifyCallUseCase()(GatherClassificationSignals(repos, repos)(next.normalizedNumber, null, null))

        assertEquals(Classification.PERSONAL, decision.classification)
        assertEquals(ClassificationSource.USER_RULE, decision.source)
    }

    @Test
    fun `a professional answer asks for a CRM lookup, a personal one never does`() = runTest {
        repos.addCall(1, number = "+919000000001")
        repos.addCall(2, number = "+919000000002")

        setClassification(2, Classification.PERSONAL, applyToNumber = true)
        assertEquals(0, repos.crmLookupRequests)

        setClassification(1, Classification.BUSINESS, applyToNumber = true)
        assertEquals(1, repos.crmLookupRequests)
    }

    @Test
    fun `an answer given in the app dismisses the number's open Work call prompts`() = runTest {
        repos.addCall(1)
        repos.addCall(2) // same number, also prompted

        setClassification(1, Classification.PERSONAL, applyToNumber = true)

        assertEquals(setOf(1L, 2L), repos.dismissedPrompts)
    }
}
