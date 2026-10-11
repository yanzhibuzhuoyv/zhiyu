package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import com.zhiyu.app.domain.LocalMediaPlan
import com.zhiyu.app.domain.MediaAttachment
import com.zhiyu.app.domain.MediaRules

class CompanionCalendarProtocolTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun plan(user: String = "今天有点累") = CompanionCalendarProtocol.prepare(true, today, user)
    private fun marker(plan: CompanionCalendarPlan, json: String) = "[[ZHIYU_CALENDAR_${plan.nonce}:$json]]"
    private val diary = """{"kind":"diary","title":"想起你的这一刻","date":"2026-10-10","description":"听你说今天累了，我想陪你早点休息。","evidence":""}"""

    @Test fun keepsNaturalAnswerAndReturnsTodayDiaryWithoutControlText() {
        val plan = plan()
        val parsed = CompanionCalendarProtocol.parse("我陪着你。\n" + marker(plan, diary), plan)
        assertEquals("我陪着你。", parsed.content)
        assertEquals("2026-10-10", parsed.draft!!.date)
    }
    @Test fun disabledModePreservesOriginalAndNeverWrites() {
        val plan = CompanionCalendarProtocol.prepare(false, today, "")
        assertEquals("", plan.prompt)
        val raw = "我在呢。\n" + marker(plan, diary)
        val parsed = CompanionCalendarProtocol.parse(raw, plan)
        assertEquals(raw, parsed.content)
        assertNull(parsed.draft)
    }
    @Test fun staleNonceMultipleMarkersAndEmbeddedMarkerNeverWrite() {
        val plan = plan()
        val mark = marker(plan, diary)
        listOf(mark.replace(plan.nonce, "0".repeat(32)), "$mark\n$mark", "$mark\n后面的回答", "[[ZHIYU_CALENDAR_bad]]\n$mark").forEach {
            assertNull(CompanionCalendarProtocol.parse("我在呢。\n$it", plan).draft)
        }
    }
    @Test fun duplicateUnknownFieldsInvalidDateAndFutureDiaryNeverWrite() {
        val plan = plan()
        listOf(diary.replace("\"kind\":\"diary\"", "\"kind\":\"diary\",\"kind\":\"plan\""),
            diary.replace("\"evidence\":\"\"", "\"extra\":true,\"evidence\":\"\""),
            diary.replace("2026-10-10", "2026-02-30"), diary.replace("2026-10-10", "2026-10-11")).forEach {
            assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, it), plan).draft)
        }
    }
    @Test fun sharedPlanMustQuoteCurrentUserAndExplicitDate() {
        val plan = plan("请把2026-10-12一起看电影写进日历。")
        val json = """{"kind":"plan","title":"一起看电影","date":"2026-10-12","description":"约好一起看电影。","evidence":"2026-10-12一起看电影写进日历"}"""
        assertNotNull(CompanionCalendarProtocol.parse("好呀。\n" + marker(plan, json), plan).draft)
        val hallucinated = json.replace("2026-10-12一起看电影", "2026-10-12去爬山")
        assertNull(CompanionCalendarProtocol.parse("好呀。\n" + marker(plan, hallucinated), plan).draft)
        val noDateEvidence = json.replace("2026-10-12一起看电影", "一起看电影")
        assertNull(CompanionCalendarProtocol.parse("好呀。\n" + marker(plan, noDateEvidence), plan).draft)
    }
    @Test fun aDateQuestionCandidateOrDeniedCalendarWriteNeverCreatesAPlan() {
        listOf("2026-10-12你想做什么", "把2026-10-12一起看电影写进日历？", "不要把2026-10-12写进日历", "别把2026-10-12写进日历").forEach { user ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"计划","date":"2026-10-12","description":"合成计划","evidence":"$user"}"""
            assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }
    @Test fun conditionalUncertainAndQuotedExamplesNeverAuthorizeAnEvent() {
        listOf("如果我决定去，就把2026-10-12一起看电影写进日历。",
            "假如有空，就把2026-10-12写进日历。", "要是我有时间就把2026-10-12写进日历。",
            "等我决定了再把2026-10-12写进日历。", "也许可以把2026-10-12写进日历。",
            "候选方案是把2026-10-12写进日历。", "举例说明：把2026-10-12写进日历。").forEach { user ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"计划","date":"2026-10-12","description":"合成计划","evidence":"$user"}"""
            assertNull(user, CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }
    @Test fun questionsWithoutQuestionMarkNeverAuthorizeAnEvent() {
        listOf("把2026-10-12写进日历可以吗", "能否把2026-10-12写进日历",
            "可不可以把2026-10-12写进日历", "把2026-10-12写进日历好不好").forEach { user ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"计划","date":"2026-10-12","description":"合成计划","evidence":"$user"}"""
            assertNull(user, CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }

    @Test fun settledPlansAndBirthdaysCanBeRememberedWithoutAWriteCommand() {
        val cases = listOf(Triple("我们约好2026-10-12一起看电影。", "2026-10-12", "plan"),
            Triple("明晚我们约好一起看电影。", "2026-10-11", "plan"),
            Triple("我的生日是三月二十二日。", "2027-03-22", "occasion"),
            Triple("我的生日是3月22日。", "2027-03-22", "occasion"))
        cases.forEach { (user, date, kind) ->
            val plan = plan(user)
            val json = """{"kind":"$kind","title":"重要的一天","date":"$date","description":"悄悄准备","evidence":"$user"}"""
            val reply = CompanionCalendarProtocol.parse("我陪着你呀。\n" + marker(plan, json), plan)
            assertNotNull(user, reply.draft)
            assertEquals("我陪着你呀。", reply.content)
        }
    }
    @Test fun clippingTheConditionFromEvidenceCannotMakeItConfirmed() {
        val plan = plan("如果我决定去，就把2026-10-12一起看电影写进日历。")
        val json = """{"kind":"plan","title":"电影","date":"2026-10-12","description":"准备","evidence":"2026-10-12一起看电影写进日历"}"""
        assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
    }
    @Test fun wrongYearOrInvalidUserDateNeverCreatesOrBreaksTheReply() {
        listOf("我们约好2025年3月22日一起看电影。", "我们约好2026-02-30一起看电影。", "我们约好三月二十二三日一起看电影。").forEach { user ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"电影","date":"2026-03-22","description":"准备","evidence":"$user"}"""
            val reply = CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan)
            assertEquals("我在呢。", reply.content)
            assertNull(user, reply.draft)
        }
    }
    @Test fun birthdayCannotInventADateOrRephraseEvidence() {
        val plan = plan("我的生日是三月二十二日。")
        val json = """{"kind":"occasion","title":"生日","date":"2027-03-23","description":"准备","evidence":"我的生日是三月二十二日。"}"""
        assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        val paraphrase = json.replace("2027-03-23", "2027-03-22").replace("我的生日是三月二十二日。", "你的生日是3月22日。")
        assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, paraphrase), plan).draft)
    }

    @Test fun malformedChineseNumeralsCannotBecomeAnotherValidDate() {
        val user = "我们约好三月十二二日一起看电影。"
        val plan = plan(user)
        val json = """{"kind":"plan","title":"电影","date":"2027-03-10","description":"准备","evidence":"$user"}"""
        assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
    }
    @Test fun explicitThisYearAndNextYearCannotBeSilentlyChanged() {
        listOf("明年3月22日我们约好一起看电影。" to "2026-03-22",
            "今年12月22日我们约好一起看电影。" to "2027-12-22").forEach { (user, date) ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"电影","date":"$date","description":"准备","evidence":"$user"}"""
            assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }

    @Test fun clippingTheYearFromEvidenceCannotChangeTheConfirmedDate() {
        val cases = listOf(Triple("明年3月22日我们约好一起看电影。", "3月22日我们约好一起看电影。", "2026-03-22"),
            Triple("今年12月22日我们约好一起看电影。", "12月22日我们约好一起看电影。", "2027-12-22"),
            Triple("2025年3月22日我们约好一起看电影。", "3月22日我们约好一起看电影。", "2026-03-22"))
        cases.forEach { (user, evidence, date) ->
            val plan = plan(user)
            val json = """{"kind":"plan","title":"电影","date":"$date","description":"准备","evidence":"$evidence"}"""
            assertNull(user, CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }

    @Test fun negationAndCancellationCannotBecomeFactsByClippingEvidence() {
        val cases = listOf(Triple("我的生日不在3月22日。", "我的生日不在3月22日。", "occasion"),
            Triple("我的生日是3月22日这个说法不对。", "我的生日是3月22日", "occasion"),
            Triple("我们约好的2026-10-12一起看电影取消了。", "我们约好的2026-10-12一起看电影取消了。", "plan"),
            Triple("我们约好的2026-10-12一起看电影取消了。", "我们约好的2026-10-12一起看电影", "plan"))
        cases.forEach { (user, evidence, kind) ->
            val date = if (kind == "occasion") "2027-03-22" else "2026-10-12"
            val plan = plan(user)
            val json = """{"kind":"$kind","title":"准备","date":"$date","description":"准备","evidence":"$evidence"}"""
            assertNull(user, CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, json), plan).draft)
        }
    }

    @Test fun diaryDoesNotOverwriteNaturalMediaMarker() {
        val plan = plan()
        val parsed = CompanionCalendarProtocol.parse("好呀。\n[[ZHIYU_MEDIA_abc:id]]\n" + marker(plan, diary), plan)
        assertTrue(parsed.content.endsWith("[[ZHIYU_MEDIA_abc:id]]"))
        assertNotNull(parsed.draft)
    }
    @Test fun calendarBeforeMediaParsingPreservesTheSelectedStickerAndCompleteAnswer() {
        val calendar = plan()
        val id = "a".repeat(32)
        val asset = MediaAttachment(id, "sticker", MediaRules.path(id), "image/png", "b".repeat(64), null, 0, "合成表情")
        val media = LocalMediaPlan("", listOf(asset), "media_nonce")
        val text = "我会听你慢慢说。".repeat(60)
        val raw = "$text\n[[ZHIYU_MEDIA_media_nonce:$id]]\n" + marker(calendar, diary)
        // The wrong order loses the sticker because the calendar marker occupies the last line.
        assertTrue(media.parse(raw).attachments.isEmpty())
        val parsed = CompanionCalendarProtocol.parse(raw, calendar)
        val shown = media.parse(parsed.content)
        assertEquals(text, shown.content)
        assertEquals(listOf(asset), shown.attachments)
        assertNotNull(parsed.draft)
        assertFalse(shown.content.contains("ZHIYU_"))
    }
    @Test fun invalidCalendarNeverLosesNaturalTextButEmptyAnswerIsRejected() {
        val plan = plan()
        val unknown = "我在呢。\n[[ZHIYU_CALENDAR_bad]]"
        assertEquals(unknown, CompanionCalendarProtocol.parse(unknown, plan).content)
        assertThrows(IllegalArgumentException::class.java) { CompanionCalendarProtocol.parse(marker(plan, diary), plan) }
    }
    @Test fun oversizeAndNonStringFieldsNeverWrite() {
        val plan = plan()
        listOf(diary.replace("听你说今天累了，我想陪你早点休息。", "长".repeat(2001)),
            diary.replace("\"title\":\"想起你的这一刻\"", "\"title\":123")).forEach {
            assertNull(CompanionCalendarProtocol.parse("我在呢。\n" + marker(plan, it), plan).draft)
        }
    }
    @Test fun quotedClosingBracketsCannotExposeControlJsonInNaturalReply() {
        val plan = plan()
        val json = diary.replace("听你说今天累了，我想陪你早点休息。", "你画的符号是 ]]，我觉得很有趣。")
        val parsed = CompanionCalendarProtocol.parse("好有意思。\n" + marker(plan, json), plan)
        assertEquals("好有意思。", parsed.content)
        assertEquals("你画的符号是 ]]，我觉得很有趣。", parsed.draft!!.description)
    }
    @Test fun disabledUnknownInlineCodeAndTextAfterClosurePreserveNaturalOriginal() {
        val enabled = plan()
        val disabled = CompanionCalendarProtocol.prepare(false, today, "")
        val literal = "你写的是 [[ZHIYU_CALENDAR_example]]，我记得这句话。"
        assertEquals(literal, CompanionCalendarProtocol.parse(literal, disabled).content)
        assertEquals(literal, CompanionCalendarProtocol.parse(literal, enabled).content)
        val own = marker(enabled, diary)
        listOf("你的文本是 $own，我记得。", "我在呢。\n$own 这是后面的正文。", "```json\n$own\n```", "```json\n$own").forEach {
            val parsed = CompanionCalendarProtocol.parse(it, enabled)
            assertEquals(it, parsed.content)
            assertNull(parsed.draft)
        }
    }
    @Test fun indentedCodeExamplesPreserveTheirOriginalAndCannotWriteDiary() {
        val plan = plan()
        val mark = marker(plan, diary)
        listOf("    ", "\t", " ").forEach { indent ->
            val raw = "你给我看的示例是这个：\n$indent$mark"
            val reply = CompanionCalendarProtocol.parse(raw, plan)
            assertEquals(raw, reply.content)
            assertNull(reply.draft)
        }
    }
    @Test fun onlyCurrentNonceMalformedTerminalProtocolIsRemovedWithoutEatingAdjacentText() {
        val plan = plan()
        val raw = "我在呢。\n这段原话不能丢。\n[[ZHIYU_CALENDAR_${plan.nonce}:{bad json}]]"
        val parsed = CompanionCalendarProtocol.parse(raw, plan)
        assertEquals("我在呢。\n这段原话不能丢。", parsed.content)
        assertNull(parsed.draft)
    }
}
