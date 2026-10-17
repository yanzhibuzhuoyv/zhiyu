package com.zhiyu.app.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class LocalPhotoBorrowRulesTest {
    @Test fun replacementKeepsActualSceneAndSlotButUsesAnotherPhotoMomentAndStableProvenance() {
        val scene = LocalDailyRules.scene("a".repeat(32), 2, Instant.parse("2026-10-17T00:00:00Z"))
        val target = LocalScenePrefetchTarget(scene, 1, scene.slots[1], true)
        val next = LocalPhotoBorrowRules.replacement(target, "b".repeat(32), "c".repeat(64))
        assertEquals(target.scene, next.scene); assertEquals(target.slot, next.slot)
        assertNotNull(next.reshoot)
        assertNotEquals(target.slot.time, next.reshoot!!.photoTime)
        assertEquals("b".repeat(32), next.reshoot!!.borrowedRequestId)
        assertEquals(next, LocalPhotoBorrowRules.replacement(target, "b".repeat(32), "c".repeat(64)))
    }
    @Test fun replacementCannotBePresentedBeforeItsActualNewCaptureMoment() {
        val tags = setOf("reshoot_${"b".repeat(32)}", "capture_0831")
        val at = Instant.parse("2026-10-17T00:31:00Z")
        assertFalse(LocalPhotoBorrowRules.ready("2026-10-17", tags, at.minusSeconds(1)))
        assertTrue(LocalPhotoBorrowRules.ready("2026-10-17", tags, at))
        assertFalse(LocalPhotoBorrowRules.ready("2026-10-17", setOf("reshoot_${"b".repeat(32)}", "capture_2561"), at))
    }
}
