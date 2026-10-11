package com.zhiyu.app.data

import com.zhiyu.app.domain.CalendarDraft
import com.zhiyu.app.domain.CareRules
import java.security.MessageDigest
import java.time.LocalDate

data class CalendarWriteOutcome(val saved: Boolean, val eventId: Long? = null, val notice: String? = null)

internal interface CompanionCalendarSink {
    fun ownedCalendar(): Long
    fun existingEvent(calendarId: Long, sourceKey: String): Long?
    fun hasDiary(calendarId: Long, date: String): Boolean
    fun insert(calendarId: Long, draft: CalendarDraft, sourceKey: String, kind: String): Long
    fun candidates(today: LocalDate, user: String): List<CompanionCalendarCandidate> = emptyList()
    fun mutate(mutation: CompanionCalendarMutation): Boolean = throw IllegalStateException("日历变更不可用")
}

/** Provider-side identity survives restarts; cancellations retain this identity to prevent resurrection. */
internal class CompanionCalendarWriter(private val sink: CompanionCalendarSink) {
    fun write(messageId: String, draft: CalendarDraft, kind: String): CalendarWriteOutcome = synchronized(lock) {
        validateMessage(messageId)
        require(kind in setOf("diary", "plan", "occasion"))
        val valid = CareRules.calendar(draft)
        val sourceKey = sha(messageId)
        val calendar = sink.ownedCalendar()
        sink.existingEvent(calendar, sourceKey)?.let { return@synchronized CalendarWriteOutcome(true, it) }
        if (kind == "diary" && sink.hasDiary(calendar, valid.date)) return@synchronized CalendarWriteOutcome(false)
        CalendarWriteOutcome(true, sink.insert(calendar, valid, sourceKey, kind))
    }
    fun mutate(messageId: String, mutation: CompanionCalendarMutation): CalendarWriteOutcome = synchronized(lock) {
        validateMessage(messageId)
        require(mutation.target.calendarId == sink.ownedCalendar()) { "不是知语自己的日历" }
        CalendarWriteOutcome(sink.mutate(mutation), mutation.target.eventId)
    }
    private fun validateMessage(messageId: String) = require(Regex("[A-Za-z0-9_-]{1,128}").matches(messageId))
    companion object {
        private val lock = Any()
        fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
