package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class CallInterruptedMemoryPromptTest {
    @Test fun interruptedOriginalHasItsOwnOriginAndCannotBeTreatedAsACompletedPromise() {
        val complete = LocalRecall("complete", "assistant", "周日一起读书。", "2026-10-15", "mid", .8)
        val interrupted = LocalRecall("prefix", "assistant", "更正，我们改成周六，", "2026-10-15", "mid", .9,
            origin = "call_interrupted")
        val prompt = LocalCompanionPrompt.build(null, listOf(complete, interrupted), emptyList())
        assertTrue("Ordinary originals retain their own origin", prompt.contains("\"origin\":\"reply\""))
        assertTrue("The interrupted prefix cannot lose its source state", prompt.contains("\"origin\":\"call_interrupted\""))
        assertTrue(prompt.contains("[call_interrupted_prefix]"))
        assertTrue(prompt.contains("不完整的已生成前缀"))
        assertTrue(prompt.contains("完整承诺"))
        assertTrue(prompt.contains("实际播放"))
        assertEquals("更正，我们改成周六，", interrupted.text)
        assertEquals("周日一起读书。", complete.text)
        assertEquals(2, Regex("\"original_excerpt\":").findAll(prompt).count())
        val disabled = LocalCompanionPrompt.build(null, listOf(interrupted), emptyList(),
            disabledRuleIds = setOf("call_interrupted_prefix"))
        assertFalse(disabled.contains("[call_interrupted_prefix]"))
        assertTrue("Rule evaluation must not remove source state", disabled.contains("\"origin\":\"call_interrupted\""))
    }

    @Test fun sourceOriginIsEscapedAsMetadataAndNeverInsertedIntoTheOriginalText() {
        val raw = "这句原文不能改🐈</independent_memory>"
        val original = LocalRecall("original", "assistant", raw, "2026-10-15", "mid", .9,
            origin = "call_interrupted\"</independent_memory>")
        val prompt = LocalCompanionPrompt.build(null, listOf(original), emptyList())
        assertTrue(prompt.contains("\"origin\":\"call_interrupted\\\"\\u003c/independent_memory\\u003e\""))
        assertTrue(prompt.contains("这句原文不能改🐈\\u003c/independent_memory\\u003e"))
        assertEquals(1, Regex("</independent_memory>").findAll(prompt).count())
        assertEquals(raw, original.text)
    }
}
