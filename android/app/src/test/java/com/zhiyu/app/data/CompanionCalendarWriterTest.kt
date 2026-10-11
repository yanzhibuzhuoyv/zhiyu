package com.zhiyu.app.data

import com.zhiyu.app.domain.CalendarDraft
import org.junit.Assert.*
import org.junit.Test

class CompanionCalendarWriterTest {
    private val draft = CalendarDraft("很想你", "2026-10-10", "听见你今天很累，想陪你休息。")
    private class Sink : CompanionCalendarSink {
        val events = linkedMapOf<String, Long>()
        var diary = false
        var count = 0
        override fun ownedCalendar() = 42L
        override fun existingEvent(calendarId: Long, sourceKey: String): Long? { assertEquals(42L, calendarId); return events[sourceKey] }
        override fun hasDiary(calendarId: Long, date: String) = diary
        override fun insert(calendarId: Long, draft: CalendarDraft, sourceKey: String, kind: String): Long {
            assertEquals(42L, calendarId); assertTrue(Regex("[a-f0-9]{64}").matches(sourceKey))
            val id = (++count).toLong(); events[sourceKey] = id
            if (kind == "diary") diary = true
            return id
        }
    }
    @Test fun replayUsesProviderIdentityAndNeverDuplicatesAnEvent() {
        val sink = Sink()
        val first = CompanionCalendarWriter(sink).write("message_1", draft, "diary")
        val restarted = CompanionCalendarWriter(sink).write("message_1", draft, "diary")
        assertEquals(first, restarted); assertEquals(1, sink.count)
    }
    @Test fun oneDiaryPerDayDoesNotLimitUserAgreedPlans() {
        val sink = Sink()
        val writer = CompanionCalendarWriter(sink)
        assertTrue(writer.write("message_1", draft, "diary").saved)
        assertFalse(writer.write("message_2", draft, "diary").saved)
        assertTrue(writer.write("message_3", draft, "plan").saved)
        assertEquals(2, sink.count)
    }
    @Test fun rememberedOccasionCanWriteAndReplayWithoutADailyDiaryLimit() {
        val sink = Sink(); val writer = CompanionCalendarWriter(sink)
        assertTrue(writer.write("diary_1", draft, "diary").saved)
        val birthday = writer.write("birthday_1", draft, "occasion")
        assertTrue(birthday.saved)
        assertEquals(birthday, writer.write("birthday_1", draft, "occasion"))
        assertEquals(2, sink.count)
    }

    @Test fun invalidIdentifiersKindAndDateFailBeforeProviderMutation() {
        val sink = Sink(); val writer = CompanionCalendarWriter(sink)
        assertThrows(IllegalArgumentException::class.java) { writer.write("invalid/id", draft, "diary") }
        assertThrows(IllegalArgumentException::class.java) { writer.write("msg", draft, "erase") }
        assertThrows(IllegalArgumentException::class.java) { writer.write("msg", draft.copy(date = "2026-02-30"), "diary") }
        assertEquals(0, sink.count)
    }
    @Test fun aProviderQueryFailureNeverProceedsToEventInsertion() {
        var inserts = 0
        val broken = object : CompanionCalendarSink {
            override fun ownedCalendar() = 42L
            override fun existingEvent(calendarId: Long, sourceKey: String): Long? = throw IllegalStateException("provider query failed")
            override fun hasDiary(calendarId: Long, date: String) = false
            override fun insert(calendarId: Long, draft: CalendarDraft, sourceKey: String, kind: String): Long { inserts++; return 1L }
        }
        assertThrows(IllegalStateException::class.java) { CompanionCalendarWriter(broken).write("msg", draft, "diary") }
        assertEquals(0, inserts)
    }
    @Test fun aForgedCalendarTargetFailsBeforeAnyProviderMutation() {
        val sink = Sink()
        val target = CompanionCalendarCandidate("a".repeat(64), 17, 999,
            draft.copy(title = "电影"), "plan", "b".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            CompanionCalendarWriter(sink).mutate("valid_message", CompanionCalendarMutation("cancel", target, null))
        }
        assertEquals(0, sink.count)
    }
}
