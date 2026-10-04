package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ConnectionRulesTest {
    @Test fun normalizesTrailingSlashes() {
        assertEquals("https://example.com/api", ConnectionRules.normalizeUrl(" https://example.com/api/// ", false))
    }
    @Test fun releaseRejectsCleartext() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.normalizeUrl("http://10.0.2.2:8790", false) }
    }
    @Test fun debugSupportsLocalBackend() {
        assertEquals("http://10.0.2.2:8790", ConnectionRules.normalizeUrl("http://10.0.2.2:8790", true))
    }
    @Test fun rejectsCredentialUrlsAndQueries() {
        listOf("https://user:secret@example.com", "https://example.com?key=secret", "file:///data/x", "https://example.com/#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectionRules.normalizeUrl(it, true) }
        }
    }
    @Test fun rejectsEmptyOrOversizeMessages() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.message("   ") }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.message("x".repeat(16001)) }
        assertEquals("你好", ConnectionRules.message(" 你好 "))
    }
    @Test fun onlyAcceptsSupportedDocuments() {
        assertEquals("notes.md", ConnectionRules.documentName("notes.md", 100))
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.documentName("image.png", 100) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.documentName("book.pdf", 5 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRules.documentName("empty.txt", 0) }
    }
}
