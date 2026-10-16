package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalContactPolicyTest {
    private val now = Instant.parse("2026-10-09T06:00:00Z")
    private val enabled = ProactivePreferences(enabled = true)
    @Test fun anxiousContactsAccelerateWhileDistantStopsAfterOneUnanswered() {
        val last = now.minusSeconds(3600)
        assertFalse(LocalContactPolicy.decide(now, enabled, "anxious", LocalContactStats(0, 0, null, last)).eligible)
        assertTrue(LocalContactPolicy.decide(now, enabled, "anxious", LocalContactStats(0, 3, null, last)).eligible)
        assertEquals("awaiting_reply", LocalContactPolicy.decide(now, enabled, "distant", LocalContactStats(0, 1, last, null)).reason)
    }
    @Test fun previousQuietHoursNoLongerBlockNightContact() {
        val atNight = Instant.parse("2026-10-09T16:00:00Z")
        val value = LocalContactPolicy.decide(atNight, enabled, "anxious", LocalContactStats())
        assertTrue(value.eligible)
        assertEquals("ready", value.reason)
        assertEquals(atNight, value.nextDue)
    }
    @Test fun capsAndRecentInteractionTakePriorityEvenForAnxiousStyles() {
        assertEquals("daily_limit", LocalContactPolicy.decide(now, enabled, "anxious", LocalContactStats(12, 5)).reason)
        assertEquals("recent_chat", LocalContactPolicy.decide(now, enabled, "anxious",
            LocalContactStats(lastActivity = now.minusSeconds(60))).reason)
    }
    @Test fun disabledAndDistantWithoutPriorInteractionNeverGenerate() {
        assertEquals("disabled", LocalContactPolicy.decide(now, ProactivePreferences(), "anxious", LocalContactStats()).reason)
        assertEquals("reserved_style", LocalContactPolicy.decide(now, enabled, "distant", LocalContactStats()).reason)
    }
    @Test fun matureAndCustomKeepTheirRespectiveLimits() {
        assertFalse(LocalContactPolicy.decide(now, enabled, "steady", LocalContactStats(unanswered = 1)).eligible)
        assertFalse(LocalContactPolicy.decide(now, enabled, "custom", LocalContactStats(todayCount = 2)).eligible)
    }
    @Test fun malformedCountersAndTimeZonesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LocalContactPolicy.decide(now, enabled, "gentle", LocalContactStats(-1)) }
        assertThrows(IllegalArgumentException::class.java) { LocalContactPolicy.decide(now,
            enabled.copy(timeZone = "Not/AZone"), "gentle", LocalContactStats()) }
    }
    @Test fun dailyCapResumesAtLocalMidnightInsteadOfOldQuietEnd() {
        val value = LocalContactPolicy.decide(now, enabled, "anxious", LocalContactStats(todayCount = 12))
        assertEquals(Instant.parse("2026-10-09T16:00:00Z"), value.nextDue)
    }
    @Test fun equalLegacyQuietHoursAreIgnored() {
        assertTrue(LocalContactPolicy.decide(now, enabled.copy(quietStart = 8, quietEnd = 8),
            "anxious", LocalContactStats()).eligible)
    }
    @Test fun firstContactUsesPersonalityAndTheActualLastInteractionInsteadOfOneBlanketWait() {
        val activity = now.minusSeconds(60)
        val gaps = mapOf("anxious" to 5L, "playful" to 10L, "warm_v1" to 15L, "warm_v2" to 15L,
            "gentle" to 20L, "custom" to 20L, "steady" to 60L, "distant" to 120L)
        gaps.forEach { (style, minutes) ->
            val stats = LocalContactStats(lastActivity = activity)
            val first = LocalContactPolicy.decide(now, enabled, style, stats)
            assertFalse(first.eligible)
            assertEquals("recent_chat", first.reason)
            assertEquals("First-contact time for $style", activity.plusSeconds(minutes * 60), first.nextDue)
            val checkedLater = LocalContactPolicy.decide(now.plusSeconds(30), enabled, style, stats)
            assertEquals("Checking again must not postpone the same interaction", first.nextDue, checkedLater.nextDue)
        }
    }
    @Test fun anxiousFirstContactDoesNotWaitFortyFiveMinutesOrRequireAnEarlierContact() {
        assertTrue(LocalContactPolicy.decide(now, enabled, "anxious",
            LocalContactStats(lastActivity = now.minusSeconds(6 * 60))).eligible)
        assertTrue(LocalContactPolicy.decide(now, enabled, "gentle",
            LocalContactStats(lastActivity = now.minusSeconds(21 * 60))).eligible)
    }
}
