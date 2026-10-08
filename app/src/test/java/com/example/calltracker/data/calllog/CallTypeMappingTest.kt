package com.example.calltracker.data.calllog

import android.provider.CallLog
import com.example.calltracker.domain.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class CallTypeMappingTest {

    @Test
    fun `call log types map to domain types`() {
        mapOf(
            CallLog.Calls.INCOMING_TYPE to CallType.INCOMING,
            CallLog.Calls.OUTGOING_TYPE to CallType.OUTGOING,
            CallLog.Calls.MISSED_TYPE to CallType.MISSED,
            CallLog.Calls.REJECTED_TYPE to CallType.REJECTED,
            CallLog.Calls.BLOCKED_TYPE to CallType.BLOCKED,
            CallLog.Calls.VOICEMAIL_TYPE to CallType.VOICEMAIL,
            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE to CallType.OTHER,
            99 to CallType.OTHER,
        ).forEach { (raw, expected) -> assertEquals("type $raw", expected, AndroidCallLogSource.mapType(raw)) }
    }
}
