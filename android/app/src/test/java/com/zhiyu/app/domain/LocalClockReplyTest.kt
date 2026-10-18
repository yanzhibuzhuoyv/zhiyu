package com.zhiyu.app.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalClockReplyTest {
    private val now = Instant.parse("2026-10-18T04:21:25Z")
    private val shanghai = ZoneId.of("Asia/Shanghai")
    @Test fun presentTimeIsAnsweredFromTheClockWithoutAGuess() {
        for (query in listOf("现在几点", "你那边现在几点了？", "能告诉我现在几点吗", "现在的时间"))
            assertEquals(query, "现在是12:21:25。", LocalClockReply.respond(query, now, shanghai))
    }
    @Test fun clockIsFreshAndUsesTheRequestedExplicitZone() {
        assertEquals("现在是12:22:25。", LocalClockReply.respond("现在几点", now.plusSeconds(60), shanghai))
        assertEquals("东京现在是13:21:25。", LocalClockReply.respond("东京现在几点", now, shanghai))
        assertEquals("现在是00:21:25。", LocalClockReply.respond("现在几点", now, ZoneId.of("America/New_York")))
    }
    @Test fun dateAndWeekdayComeFromTheSameCurrentSnapshot() {
        assertEquals("今天是2026年10月18日，星期日。", LocalClockReply.respond("今天几号？", now, shanghai))
        assertEquals("今天星期日。", LocalClockReply.respond("今天星期几", now, shanghai))
    }
    @Test fun userStatementsAndOtherQuestionsAreNeverSilentlySwallowed() {
        for (query in listOf("现在是八点", "如果现在八点你会做什么", "现在几点，顺便给我发张自拍", "明天几点见", "你记得我昨天几点回家吗", "现在几点\n我今天很难过", "几点回家"))
            assertNull(query, LocalClockReply.respond(query, now, shanghai))
    }
}
