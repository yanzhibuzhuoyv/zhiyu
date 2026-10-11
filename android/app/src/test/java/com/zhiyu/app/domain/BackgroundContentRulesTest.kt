package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class BackgroundContentRulesTest {
    private val clear = "{\"schema\":\"background-content-v1\",\"subject_free\":true,\"person\":false,\"body\":false,\"face\":false,\"character\":false,\"uncertain\":false}"
    @Test fun acceptsOnlyAnExplicitSubjectFreeDecision() { assertTrue(BackgroundContentRules.accepts(clear)) }
    @Test fun anyPersonBodyFaceOrCharacterRejectsTheBackground() {
        listOf("person", "body", "face", "character", "uncertain").forEach { name ->
            assertFalse(name, BackgroundContentRules.accepts(clear.replace("\"$name\":false", "\"$name\":true")))
        }
        assertFalse(BackgroundContentRules.accepts(clear.replace("\"subject_free\":true", "\"subject_free\":false")))
    }
    @Test fun malformedUnknownDuplicateAndNonBooleanFieldsFailClosed() {
        listOf("", "{}", "```json\n$clear\n```", clear.replace("false", "\"false\""),
            clear.replace("v1", "v2"), clear.dropLast(1) + ",\"other\":false}",
            clear.dropLast(1) + ",\"person\":true}", clear.replace(",\"uncertain\":false", ""),
            clear + "x").forEach { assertFalse(BackgroundContentRules.accepts(it)) }
    }
    @Test fun excessiveAndInvalidUnicodeContentFailClosed() {
        assertFalse(BackgroundContentRules.accepts("x".repeat(8193)))
        assertFalse(BackgroundContentRules.accepts("\ud800"))
    }
}
