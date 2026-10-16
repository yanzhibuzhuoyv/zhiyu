package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProactiveSchedulePolicyTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Test fun persistedDueIsKeptAcrossChecksInsteadOfResetToAnotherQuarterHour() {
        val due = now.plusSeconds(5 * 3600)
        val record = ProactiveRunRecord(now, "not_due", due)
        assertEquals(due, ProactiveSchedulePolicy.nextCheck(now, record))
        assertEquals(due, ProactiveSchedulePolicy.nextCheck(now.plusSeconds(60), record))
    }

    @Test fun staleAndMissingDueNeverCauseAnImmediateBackgroundBusyLoop() {
        assertEquals(now.plusSeconds(30), ProactiveSchedulePolicy.nextCheck(now, ProactiveRunRecord(now, "sent", now.minusSeconds(1))))
        assertEquals(now.plusSeconds(30), ProactiveSchedulePolicy.nextCheck(now, null))
        assertEquals(now.plusSeconds(900), ProactiveSchedulePolicy.nextCheck(now, ProactiveRunRecord(now, "foreground", null)))
        assertEquals(now.plusSeconds(86400), ProactiveSchedulePolicy.nextCheck(now,
            ProactiveRunRecord(now, "not_due", now.plusSeconds(86400 * 8))))
    }

    @Test fun oneConversationUsesOneStableWorkChainAcrossChangingDueTimes() {
        assertEquals("zhiyu_companion_due_chat", ProactiveSchedulePolicy.uniqueDueName("chat"))
        assertNotEquals(ProactiveSchedulePolicy.uniqueDueName("chat"), ProactiveSchedulePolicy.uniqueDueName("other_chat"))
        assertEquals(30_000L, ProactiveSchedulePolicy.delayMillis(now, now.plusSeconds(30)))
    }

    @Test fun recordsNeverAcceptRawErrorMessagesAndDoNotFabricateAnUnknownDue() {
        assertNull(ProactiveSchedulePolicy.parseNextDue(null))
        assertThrows(IllegalArgumentException::class.java) { ProactiveRunRecord(now, "Key=secret", null) }
        assertThrows(IllegalArgumentException::class.java) { ProactiveSchedulePolicy.uniqueDueName("../chat") }
    }
    @Test fun firstAllowedCheckDoesNotWaitAnExtraQuarterHourBeforeEvaluatingThePersonality() {
        assertEquals(now.plusSeconds(30), ProactiveSchedulePolicy.nextCheck(now, null))
    }
}
