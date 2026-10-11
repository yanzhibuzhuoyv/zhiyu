package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalDayStockRulesTest {
    private fun asset(day: String?, version: Int) = MediaAttachment("a".repeat(32), "image", "/api/media/assets/${"a".repeat(32)}",
        "image/png", "b".repeat(64), day, version, "生活画面")
    @Test fun futureAndOtherDayStockNeverPretendsToHaveHappened() {
        assertFalse(LocalDayStockRules.available(asset("2026-10-10", 3), setOf("daily_stock", "slot_1830"), "2026-10-10", "12:00", 3))
        assertFalse(LocalDayStockRules.available(asset("2026-10-09", 3), setOf("daily_stock", "slot_0730"), "2026-10-10", "12:00", 3))
        assertTrue(LocalDayStockRules.available(asset("2026-10-10", 3), setOf("daily_stock", "slot_0730"), "2026-10-10", "12:00", 3))
    }
    @Test fun staleCharacterVersionIsRejectedEvenWithTodaysLabel() {
        assertFalse(LocalDayStockRules.available(asset("2026-10-10", 2), setOf("daily_stock", "contains_person", "slot_0730"), "2026-10-10", "12:00", 3))
        assertTrue(LocalDayStockRules.available(asset(null, 0), setOf("landscape"), "2026-10-10", "12:00", 3))
    }
    @Test fun everySceneHasDeterministicViewButNoArtificialWingCount() {
        assertEquals(LocalDayStockRules.view(2), LocalDayStockRules.view(2))
        assertEquals("portrait", LocalDayStockRules.view(1))
        assertEquals("pov", LocalDayStockRules.view(2))
    }
}
