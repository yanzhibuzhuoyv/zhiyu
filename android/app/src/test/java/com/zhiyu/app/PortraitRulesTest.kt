package com.zhiyu.app

import com.zhiyu.app.domain.PortraitPreview
import com.zhiyu.app.domain.PortraitRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortraitRulesTest {
    private val original = "b".repeat(32)
    private val subject = "c".repeat(32)
    private val valid = PortraitPreview("a".repeat(32), original, subject,
        "/api/media/assets/$original", "/api/media/assets/$subject", "d".repeat(64), "e".repeat(64),
        640, 480, listOf(30, 20, 600, 470), 2, "u2netp", true)
    @Test fun subjectPreviewAlwaysRequiresConfirmation() {
        assertEquals(valid, PortraitRules.check(valid))
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(needsConfirmation = false)) }
    }
    @Test fun rejectsForeignPathsAndInvalidDigests() {
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(subjectUrl = "https://other.example/photo.png")) }
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(originalSha256 = "bad")) }
    }
    @Test fun rejectsCoordinatesOutsideOriginalImageAndMalformedShape() {
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(bounds = listOf(-1, 0, 100, 100))) }
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(bounds = listOf(0, 0, 700, 480))) }
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(bounds = listOf(0, 0))) }
    }
    @Test fun boundedWorkingCanvasKeepsSeparateOriginalDimensions() {
        val working = valid.copy(coordinateSpace = "working_pixels", sourceWidth = 4000, sourceHeight = 3000)
        assertEquals(4000, PortraitRules.check(working).sourceWidth)
        assertEquals(640, PortraitRules.check(working).originalWidth)
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(working.copy(originalWidth = 2000)) }
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(working.copy(sourceWidth = 9000)) }
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(working.copy(coordinateSpace = "unknown")) }
    }
    @Test fun manualSelectionAndRefinementBothStillRequireConfirmation() {
        assertEquals("manual_selection", PortraitRules.check(valid.copy(method = "manual_selection")).method)
        assertEquals("manual_refinement", PortraitRules.check(valid.copy(method = "manual_refinement")).method)
        assertThrows(IllegalArgumentException::class.java) { PortraitRules.check(valid.copy(method = "manual_selection", needsConfirmation = false)) }
    }
}
