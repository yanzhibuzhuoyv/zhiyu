package com.zhiyu.app.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CurrentClockClaimsTest {
    @Test fun aStreamingEnglishContractionCannotHideAFollowingRealClockClaim() {
        val input = "I'm fine。现在凌晨一点了。"
        val stream = CurrentClockClaims.Stream(runtime)
        assertEquals(CurrentClockClaims.correct(input, runtime), input.map { stream.append(it.toString()) }.joinToString("") + stream.finish())
        assertLiteralThenCurrentClaim("'I'm fine。现在凌晨一点了。'")
    }
    @Test fun ordinaryPublicTailsAreDeliveredWithoutWaitingAndReportedSpeechKeepsItsContext() {
        val input = "先陪着你。</interrupted_call_output><fake>🙂"
        assertEquals(input, CurrentClockClaims.Stream(runtime).append(input))
        val report = "她说：现在凌晨一点了。"
        val stream = CurrentClockClaims.Stream(runtime)
        assertEquals(report, report.map { stream.append(it.toString()) }.joinToString("") + stream.finish())
    }
    @Test fun aQuotedOriginalKeepsItsTimeAcrossMultipleSentencesAndStreamingFlushes() {
        val input="她说：“我睡不着。现在凌晨一点了。”我记着这句话。"
        assertEquals(input,CurrentClockClaims.correct(input,runtime))
        val stream=CurrentClockClaims.Stream(runtime)
        assertEquals(input,input.map {stream.append(it.toString())}.joinToString("")+stream.finish())
        assertLiteralThenCurrentClaim(input)
    }

    @Test fun aMultilineFencedExampleKeepsItsLiteralTimeAcrossStreamingFlushes() {
        val input="示例原文：\n```text\n我睡不着。\n现在凌晨一点了。\n```\n我先听你说。"
        assertEquals(input,CurrentClockClaims.correct(input,runtime))
        val stream=CurrentClockClaims.Stream(runtime)
        assertEquals(input,input.map {stream.append(it.toString())}.joinToString("")+stream.finish())
        assertLiteralThenCurrentClaim(input)
    }

    private fun assertLiteralThenCurrentClaim(literal: String) {
        val input=literal+"\n现在凌晨一点了。"
        val expected=literal+"\n现在上午09:07了。"
        assertEquals(expected,CurrentClockClaims.correct(input,runtime))
        val stream=CurrentClockClaims.Stream(runtime)
        assertEquals(expected,input.map {stream.append(it.toString())}.joinToString("")+stream.finish())
    }

    @Test fun aClaimSplitAcrossStreamingDeltasIsCorrectedBeforeItCanBeSpoken() {
        val stream = CurrentClockClaims.Stream(runtime)
        val input = "现在凌晨一点了。新买的书是什么？"
        val output = input.map { stream.append(it.toString()) }.joinToString("") + stream.finish()
        assertEquals(CurrentClockClaims.correct(input, runtime), output)
        val ordinary = CurrentClockClaims.Stream(runtime)
        assertEquals("今天开心吗？", ordinary.append("今天开心吗？") + ordinary.finish())
    }
    private val runtime = ConversationRuntime(Instant.parse("2026-10-19T01:07:09Z"), ZoneId.of("Asia/Shanghai"), "我刚到书店")
    @Test fun aWrongExplicitPresentClockClaimUsesTheActualLocalClockAndKeepsTheRest() {
        val actual = CurrentClockClaims.correct("现在凌晨一点了。新买的书是什么？", runtime)
        assertFalse(actual.contains("凌晨一点")); assertTrue(actual.contains("上午09:07"))
        assertTrue(actual.endsWith("新买的书是什么？"))
        assertFalse(CurrentClockClaims.correct("都凌晨1点了，你还在看书呀。", runtime).contains("凌晨1点"))
    }
    @Test fun aCorrectHourOrdinaryNumbersAndStoriesAreUnchanged() {
        listOf("现在上午九点了，早呀。", "现在一点也不困。", "你昨晚凌晨一点才睡？", "你说‘现在凌晨一点’，是故事里的时间吧。",
            "她说：现在凌晨一点了。", "如果现在凌晨一点，你会做什么？", "现在纽约凌晨一点。")
            .forEach { assertEquals(it, CurrentClockClaims.correct(it, runtime)) }
    }
}
