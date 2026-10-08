package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class MediaRulesTest {
    private fun value(url: String = MediaRules.path("a".repeat(32))) = MediaAttachment("a".repeat(32), "image", url,
        "image/png", "b".repeat(64), "2030-01-02", 1, "虚构OC样例")
    @Test fun ownedRelativePathAccepted() { assertEquals(value(), MediaRules.check(value())) }
    @Test fun externalRedirectAndOtherAssetRejected() {
        listOf("https://elsewhere.test/a.png", "/api/media/assets/" + "c".repeat(32)).forEach {
            assertThrows(IllegalArgumentException::class.java) { MediaRules.check(value(it)) }
        }
    }
    @Test fun unsupportedTypeRejected() {
        assertThrows(IllegalArgumentException::class.java) { MediaRules.check(value().copy(mimeType = "image/svg+xml")) }
    }
    @Test fun oldMessagesRemainCompatible() {
        val old = Message("one", "assistant", "你好", emptyList(), "2030-01-02T12:00:00Z")
        assertTrue(old.mediaAttachments.isEmpty())
        assertEquals("reply", old.origin)
    }
}
