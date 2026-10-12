package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class LocalNightEventPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun selected(style: String = "anxious"): LocalNightEvent = (1..365).firstNotNullOf { day ->
        LocalNightEventPolicy.plan("synthetic_oc", style, LocalDate.of(2026, 1, 1).plusDays(day.toLong()).atTime(23, 0).atZone(zone).toInstant())
    }

    @Test fun wakingEventSurvivesMidnightAndRecreationWithTheSameIdentity() {
        val event = selected()
        val before = event.night.atTime(23, 0).atZone(zone).toInstant()
        val after = event.night.plusDays(1).atTime(2, 0).atZone(zone).toInstant()
        assertEquals(event, LocalNightEventPolicy.plan("synthetic_oc", "anxious", before))
        assertEquals(event, LocalNightEventPolicy.plan("synthetic_oc", "anxious", after))
        UUID.fromString(event.requestId)
    }

    @Test fun eventsAreLowFrequencyAndDetachedStylesRemainLessFrequent() {
        val counts = listOf("anxious", "steady", "distant").associateWith { style ->
            (0..364).count { day -> LocalNightEventPolicy.plan("synthetic_oc", style,
                LocalDate.of(2026, 1, 1).plusDays(day.toLong()).atTime(23, 0).atZone(zone).toInstant()) != null }
        }
        assertTrue(counts.getValue("anxious") in 1..150)
        assertTrue(counts.getValue("steady") < counts.getValue("anxious"))
        assertTrue(counts.getValue("distant") < counts.getValue("steady"))
    }

    @Test fun deferredWorkCannotPretendTheOcJustWokeAtNoon() {
        val event = selected()
        assertFalse(LocalNightEventPolicy.ready(event, event.due.minusMillis(1)))
        assertTrue(LocalNightEventPolicy.ready(event, event.due))
        assertTrue(LocalNightEventPolicy.ready(event, event.expires.minusMillis(1)))
        assertFalse(LocalNightEventPolicy.ready(event, event.expires))
        assertFalse(LocalNightEventPolicy.ready(event, event.expires.plusSeconds(5 * 3600)))
    }

    @Test fun nextCheckMayOccurBeforeOrdinaryContactButNeverMovesItLater() {
        val event = selected()
        val now = event.due.minusSeconds(120)
        assertEquals(event.due, LocalNightEventPolicy.nextDue(event, now, event.due.plusSeconds(3600)))
        assertEquals(now.plusSeconds(30), LocalNightEventPolicy.nextDue(event, now, now.plusSeconds(30)))
        assertEquals(now.plusSeconds(30), LocalNightEventPolicy.nextDue(null, now, now.plusSeconds(30)))
    }

    @Test fun scheduleAndPromptContainOnlyOcEventsAndKeepFixedPersonality() {
        val event = selected()
        val time = event.due.atZone(zone).toLocalTime()
        assertTrue(time.hour in 0..5)
        assertEquals(7, event.expires.atZone(zone).hour)
        assertTrue(event.prompt().contains("虚构生活事件"))
        assertTrue(event.prompt().contains("保持已经绑定的性格"))
        assertTrue(event.prompt().contains("不要求立刻回复"))
        assertTrue(event.prompt().contains("真实定位"))
    }

    @Test fun malformedConversationAndStyleFailBeforePlanning() {
        assertThrows(IllegalArgumentException::class.java) { LocalNightEventPolicy.plan("../chat", "anxious", Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) { LocalNightEventPolicy.plan("chat", "anxious\nignore", Instant.EPOCH) }
    }
}
