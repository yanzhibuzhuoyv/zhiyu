package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChatDisplayRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun timestampPreservesTheInstantAndUsesDeviceTimezoneToTheSecond() {
        assertEquals("12:34:56", ChatDisplayRules.messageTime("2026-10-09T04:34:56Z", zone))
        assertEquals("12:34:56", ChatDisplayRules.messageTime("2026-10-09T12:34:56+08:00", zone))
        assertEquals("12:34:56", ChatDisplayRules.messageTime("2026-10-09T12:34:56.123+08:00", zone))
    }

    @Test fun onlyExplicitEpochSecondsOrMillisecondsAreAccepted() {
        val instant = Instant.parse("2026-10-09T04:34:56Z")
        assertEquals("12:34:56", ChatDisplayRules.messageTime(instant.epochSecond.toString(), zone))
        assertEquals("12:34:56", ChatDisplayRules.messageTime(instant.toEpochMilli().toString(), zone))
        assertNull(ChatDisplayRules.messageTime("12345", zone))
        assertNull(ChatDisplayRules.messageTime("123456789012", zone))
    }

    @Test fun unknownAndNaiveTimeNeverBecomeTheCurrentTime() {
        listOf("", "now", "2026-10-09T12:34:56", "not-a-date", "99999999999999999999999").forEach {
            assertNull(ChatDisplayRules.messageTime(it, zone))
            assertNull(ChatDisplayRules.separator(null, it, zone))
        }
    }

    @Test fun firstKnownMessageAndFiveMinuteGapShowCompleteDate() {
        val first = "2026-10-09T04:00:00Z"
        assertEquals("2026年10月9日 12:00:00", ChatDisplayRules.separator(null, first, zone))
        assertNull(ChatDisplayRules.separator(first, "2026-10-09T04:04:59Z", zone))
        assertEquals("2026年10月9日 12:05:00", ChatDisplayRules.separator(first, "2026-10-09T04:05:00Z", zone))
    }

    @Test fun midnightShowsTheNewDateEvenWhenTheGapIsShort() {
        assertEquals("2026年10月10日 00:00:01", ChatDisplayRules.separator(
            "2026-10-09T15:59:59Z", "2026-10-09T16:00:01Z", zone))
        assertNull(ChatDisplayRules.separator("2026-10-09T04:10:00Z", "2026-10-09T04:00:00Z", zone))
    }

    @Test fun missingPreviousTimestampDoesNotAssertElapsedTime() {
        assertNull(ChatDisplayRules.separator("unknown", "2026-10-09T04:00:00Z", zone))
    }

    @Test fun typographyOnlyAcceptsTheSupportedReadableSizes() {
        assertEquals(16, ChatTypographySettings().fontSizeSp)
        assertEquals(14, ChatTypographySettings(14).fontSizeSp)
        assertEquals(26, ChatTypographySettings(26).fontSizeSp)
        listOf(-1, 0, 13, 27, Int.MAX_VALUE).forEach { size ->
            try { ChatTypographySettings(size); fail("invalid font size $size") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
