package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification.BUSINESS
import com.example.calltracker.domain.model.Classification.PERSONAL
import com.example.calltracker.domain.model.Classification.UNCLASSIFIED
import com.example.calltracker.domain.model.ClassificationDecision
import com.example.calltracker.domain.model.ClassificationSignals
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifyCallUseCaseTest {

    private val classify = ClassifyCallUseCase()
    private val match = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya", null)

    @Test
    fun `no signals leaves the call unclassified - the user is asked`() {
        assertEquals(ClassificationDecision(UNCLASSIFIED, ClassificationSource.NONE), classify(ClassificationSignals()))
    }

    @Test
    fun `the number's saved answer beats the work SIM`() {
        val decision = classify(ClassificationSignals(userRule = PERSONAL, crmMatch = match, isOnWorkSim = true))
        assertEquals(ClassificationDecision(PERSONAL, ClassificationSource.USER_RULE), decision)
    }

    @Test
    fun `a CRM record alone doesn't decide - classification comes first`() {
        val decision = classify(ClassificationSignals(crmMatch = match))
        assertEquals(ClassificationDecision(UNCLASSIFIED, ClassificationSource.NONE), decision)
    }

    @Test
    fun `work SIM is professional without asking`() {
        val decision = classify(ClassificationSignals(isOnWorkSim = true))
        assertEquals(ClassificationDecision(BUSINESS, ClassificationSource.WORK_SIM), decision)
    }

    @Test
    fun `an UNCLASSIFIED rule is ignored`() {
        val decision = classify(ClassificationSignals(userRule = UNCLASSIFIED, isOnWorkSim = true))
        assertEquals(ClassificationSource.WORK_SIM, decision.source)
    }
}
