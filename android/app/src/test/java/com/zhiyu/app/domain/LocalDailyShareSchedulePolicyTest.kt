package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalDailyShareSchedulePolicyTest {
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    @Test fun aNewOptInChecksSoonRatherThanWaitingForTheGenericContactInterval() {
        assertEquals(now.plusSeconds(30), LocalDailyShareSchedulePolicy.nextCheck(now, null))
    }

    @Test fun anImageDeferralAndUpcomingSlotRemainStableAcrossRepeatedAppRepairs() {
        val imageDue = now.plusSeconds(900)
        val record = ProactiveRunRecord(now, "not_due", imageDue)
        assertEquals(imageDue, LocalDailyShareSchedulePolicy.nextCheck(now, record))
        assertEquals(imageDue, LocalDailyShareSchedulePolicy.nextCheck(now.plusSeconds(60), record))
    }

    @Test fun overdueAndMalformedLongFutureSchedulesAreBoundedWithoutBusyLooping() {
        assertEquals(now.plusSeconds(30), LocalDailyShareSchedulePolicy.nextCheck(now,
            ProactiveRunRecord(now, "sent", now.minusSeconds(1))))
        assertEquals(now.plusSeconds(86400), LocalDailyShareSchedulePolicy.nextCheck(now,
            ProactiveRunRecord(now, "not_due", now.plusSeconds(86400 * 8))))
        assertEquals(1L, LocalDailyShareSchedulePolicy.delayMillis(now, now.minusSeconds(1)))
    }

    @Test fun dailySharesHaveTheirOwnStableWorkNameAndValidatedConversationId() {
        val name = LocalDailyShareSchedulePolicy.uniqueDueName("chat")
        assertEquals(name, LocalDailyShareSchedulePolicy.uniqueDueName("chat"))
        assertNotEquals(name, ProactiveSchedulePolicy.uniqueDueName("chat"))
        assertNotEquals(name, LocalDailyShareSchedulePolicy.uniqueDueName("other"))
        assertThrows(IllegalArgumentException::class.java) { LocalDailyShareSchedulePolicy.uniqueDueName("../chat") }
    }
}
