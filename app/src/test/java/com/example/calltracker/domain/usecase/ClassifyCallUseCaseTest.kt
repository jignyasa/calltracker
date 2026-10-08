package com.example.calltracker.domain.usecase

import com.example.calltracker.domain.model.Classification.BUSINESS
import com.example.calltracker.domain.model.Classification.PERSONAL
import com.example.calltracker.domain.model.Classification.UNCLASSIFIED
import com.example.calltracker.domain.model.ClassificationSource
import com.example.calltracker.domain.model.CrmMatch
import com.example.calltracker.domain.model.CrmRecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassifyCallUseCaseTest {

    private val classify = ClassifyCallUseCase()
    private val match = CrmMatch(CrmRecordType.CONTACT, "con_1", "Ananya", null)

    @Test
    fun `no signals leaves the call unclassified`() {
        assertEquals(ClassificationDecision(UNCLASSIFIED, ClassificationSource.NONE), classify(ClassificationSignals()))
    }

    @Test
    fun `user rule beats every automatic signal`() {
        val decision = classify(
            ClassificationSignals(userRule = PERSONAL, crmMatch = match, isOnWorkSim = true, isDeviceContact = false),
        )
        assertEquals(ClassificationDecision(PERSONAL, ClassificationSource.USER_RULE), decision)
    }

    @Test
    fun `crm match is business even for a saved device contact`() {
        val decision = classify(ClassificationSignals(crmMatch = match, isDeviceContact = true))
        assertEquals(ClassificationDecision(BUSINESS, ClassificationSource.CRM_MATCH), decision)
    }

    @Test
    fun `work sim is business even for a saved device contact`() {
        val decision = classify(ClassificationSignals(isOnWorkSim = true, isDeviceContact = true))
        assertEquals(ClassificationDecision(BUSINESS, ClassificationSource.WORK_SIM), decision)
    }

    @Test
    fun `device contact outside the crm is personal`() {
        val decision = classify(ClassificationSignals(isDeviceContact = true))
        assertEquals(ClassificationDecision(PERSONAL, ClassificationSource.DEVICE_CONTACT), decision)
    }

    @Test
    fun `an UNCLASSIFIED rule is ignored`() {
        val decision = classify(ClassificationSignals(userRule = UNCLASSIFIED, crmMatch = match))
        assertEquals(ClassificationSource.CRM_MATCH, decision.source)
    }
}
