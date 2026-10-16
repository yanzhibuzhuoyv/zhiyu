package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ConversationalRhythmPromptTest {
    @Test fun presetsEncourageShortNaturalIndependentMessagesInsteadOfThreeLongSentences() {
        listOf("gentle", "playful", "steady", "anxious", "distant", "warm_v1", "warm_v2").forEach { style ->
            val prompt = LocalCompanionPrompt.build(CompanionProfile(style = style), emptyList(), emptyList())
            assertTrue(prompt.contains("[conversational_rhythm]"))
            assertTrue(prompt.contains("独立消息之间换行"))
            assertTrue(prompt.contains("不凑三句话"))
            assertTrue(prompt.contains("不设最低字数"))
            assertTrue(prompt.contains("复杂问题"))
        }
    }

    @Test fun reservedAndAttachedStylesDoNotUseOneSharedLengthTemplate() {
        fun prompt(style: String) = LocalCompanionPrompt.build(CompanionProfile(style = style), emptyList(), emptyList())
        assertTrue(prompt("distant").contains("克制，常常一条就够"))
        assertTrue(prompt("steady").contains("话不多，先把具体意思说清"))
        assertTrue(prompt("anxious").contains("几条短消息"))
        assertTrue(prompt("playful").contains("短句和轻快的停顿"))
    }

    @Test fun naturalLanguagePatchPreservesTheUsersFixedCoreAndRawMemoryEvidence() {
        val profile = CompanionProfile(style = "warm_v2", name = "测试OC")
        val saved = CompanionBinding("deepseek", "synthetic-model", profile)
        val prompt = LocalCompanionPrompt.build(profile, emptyList(), emptyList())
        assertTrue(prompt.contains("不永久改变这些核心"))
        assertTrue(prompt.contains("[absence_is_not_evidence]"))
        assertTrue(prompt.contains("不每次复述用户"))
        assertTrue(prompt.contains("不把普通分享写成心理咨询"))
        CompanionRules.checkChange(saved, profile)
        assertThrows(IllegalArgumentException::class.java) { CompanionRules.checkChange(saved, profile.copy(style = "playful")) }
    }
}
