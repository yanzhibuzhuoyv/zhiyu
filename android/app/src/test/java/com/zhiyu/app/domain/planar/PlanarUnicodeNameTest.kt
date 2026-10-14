package com.zhiyu.app.domain.planar

import org.junit.Assert.*
import org.junit.Test

class PlanarUnicodeNameTest {
    @Test fun eightyUnicodeCodePointsAreAcceptedForDesktopCompatibility() {
        val name = "🦋".repeat(80)
        assertEquals(name, PlanarDraft.validName(name))
    }

    @Test fun eightyOneUnicodeCodePointsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PlanarDraft.validName("🦋".repeat(81)) }
    }
}
