package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalMemoryRecognitionJsonTest {
    private val text = "我的生日是九月十八"
    private val source = LocalMemoryOriginal("owner", "chat", "message", "user", "2026-10-09T00:00:00Z", LocalMemoryRecognitionRules.sha(text), 0, text)
    private val window = LocalMemoryWindow(source, 0, text.length)
    private val claim = """{"quote":"我的生日是九月十八","kind":"profile","subject":"user","polarity":"affirm","topic_key":"birthday","confidence":0.95,"explicit_keep":false,"correction":false,"promise_state":null,"date_basis":null,"event_date":null}"""
    private fun parse(claimText: String = claim) = LocalMemoryRecognitionJson.parse("{\"claims\":[$claimText]}", window)

    @Test fun strictJsonReturnsOnlyGroundedPointersAndCanSuccessfullyRecognizeNothing() {
        assertEquals("user:birthday", parse().single().topicKey)
        assertTrue(LocalMemoryRecognitionJson.parse("{\"claims\":[]}", window).isEmpty())
    }
    @Test fun duplicateFieldsAtAnyLevelCannotOverwriteAQuoteOrConfidence() {
        val duplicate = claim.replace("\"confidence\":0.95", "\"confidence\":0.95,\"confidence\":0.0")
        assertThrows(IllegalArgumentException::class.java) { parse(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionJson.parse("{\"claims\":[],\"claims\":[$claim]}", window) }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryStrictJson.parse("{\"a\":{\"x\":null,\"x\":1}}") }
    }
    @Test fun nonfiniteBooleanAndNumericStringsCannotPassAsConfidence() {
        listOf("NaN", "Infinity", "1e999", "true", "\"0.95\"").forEach { number ->
            assertThrows(IllegalArgumentException::class.java) { parse(claim.replace("0.95", number)) }
        }
    }
    @Test fun extraFieldsMarkdownAndInvalidJsonCannotSmuggleAnAlternateBody() {
        assertThrows(IllegalArgumentException::class.java) { parse(claim.dropLast(1) + ",\"summary\":\"新事实\"}") }
        listOf("```json\n{\"claims\":[]}\n```", "{claims:[]}", "{\"claims\":[],}", "{\"claims\":[]}tail").forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionJson.parse(it, window) }
        }
    }
    @Test fun duplicateClaimsAndFractionalPositionsAreRejectedAtomically() {
        assertThrows(IllegalArgumentException::class.java) { parse("$claim,$claim") }
        assertThrows(IllegalArgumentException::class.java) { parse(claim.dropLast(1) + ",\"start\":0.5,\"end\":10}") }
    }
    @Test fun unpairedSurrogatesNestingAndResponseLimitsFailWithoutEchoingTheInput() {
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryStrictJson.parse("\"\\uD83D\"") }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryStrictJson.parse("[".repeat(18) + "0" + "]".repeat(18)) }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryStrictJson.parse(" ".repeat(65537)) }
        val original = "😀\n<原文>\t\\"
        assertEquals(original, LocalMemoryStrictJson.parse(LocalMemoryStrictJson.quote(original)))
    }
}
