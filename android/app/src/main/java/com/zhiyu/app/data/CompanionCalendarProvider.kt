package com.zhiyu.app.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.zhiyu.app.domain.CalendarDraft
import com.zhiyu.app.domain.CareRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** All reads/writes are scoped to the dedicated local calendar and verified app-source identity. */
internal class AndroidCompanionCalendarSink(context: Context) : CompanionCalendarSink {
    private val resolver = context.contentResolver
    private val account = context.packageName
    private val calendarName = "zhiyu_companion_diary_v1"
    private val sourcePrefix = "zhiyu://companion-calendar/"
    private val identity = "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars.NAME}=?"
    private val eventIdentity = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=? AND ${CalendarContract.Events.DELETED}=0"
    private val columns = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.CUSTOM_APP_PACKAGE, CalendarContract.Events.CUSTOM_APP_URI,
        CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND, CalendarContract.Events.DURATION, CalendarContract.Events.RRULE,
        CalendarContract.Events.STATUS, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_TIMEZONE,
        CalendarContract.Events.HAS_ALARM)

    private fun findCalendar(): Long? = (resolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID), identity, arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL, calendarName), null)
        ?: throw IllegalStateException("系统日历查询失败")).use { if (it.moveToFirst()) it.getLong(0) else null }

    override fun ownedCalendar(): Long {
        findCalendar()?.let { return it }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, calendarName)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "知语 · 我们的日记")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF925349.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1); put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
            put(CalendarContract.Calendars.ALLOWED_REMINDERS, CalendarContract.Reminders.METHOD_ALERT.toString())
            put(CalendarContract.Calendars.MAX_REMINDERS, 2)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        return ContentUris.parseId(resolver.insert(uri, values) ?: throw IllegalStateException("系统未创建知语日历"))
    }

    override fun existingEvent(calendarId: Long, sourceKey: String): Long? = queryEvent(eventIdentity,
        arrayOf(calendarId.toString(), account, sourceUri(sourceKey)))

    override fun hasDiary(calendarId: Long, date: String): Boolean {
        val start = CareRules.calendarTimes(CalendarDraft("日记", date, "")).first
        return queryEvent("${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.DTSTART}=? AND ${CalendarContract.Events.DESCRIPTION} LIKE ? AND ${CalendarContract.Events.DELETED}=0",
            arrayOf(calendarId.toString(), account, start.toString(), "[恋人日记]%")) != null
    }

    override fun insert(calendarId: Long, draft: CalendarDraft, sourceKey: String, kind: String): Long {
        val schedule = CompanionCalendarSchedule.of(draft, kind)
        val event = eventValues(draft, kind, schedule).apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, account)
            put(CalendarContract.Events.CUSTOM_APP_URI, sourceUri(sourceKey))
        }
        val operations = arrayListOf(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(event).build())
        schedule.reminders.forEach { minutes -> operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
            .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
            .withValue(CalendarContract.Reminders.MINUTES, minutes)
            .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build() }
        val result = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return ContentUris.parseId(result.first().uri ?: throw IllegalStateException("系统未保存日历"))
    }

    override fun candidates(today: LocalDate, user: String): List<CompanionCalendarCandidate> {
        // Only explicit cancel/change wording opens this path; ordinary daily sharing does not query events.
        if (!Regex("取消|撤销|作废|撤回|作罢|改到|改为|改成|更正为").containsMatchIn(user)) return emptyList()
        val calendar = findCalendar() ?: return emptyList()
        val where = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI} LIKE ? AND ${CalendarContract.Events.DELETED}=0 AND (${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS}!=?)"
        return (resolver.query(CalendarContract.Events.CONTENT_URI, columns, where,
            arrayOf(calendar.toString(), account, "$sourcePrefix%", CalendarContract.Events.STATUS_CANCELED.toString()),
            "${CalendarContract.Events.DTSTART} DESC") ?: throw IllegalStateException("系统日历查询失败")).use { cursor ->
            val result = mutableListOf<CompanionCalendarCandidate>()
            var visited = 0
            while (cursor.moveToNext() && visited++ < 128 && result.size < 32) {
                val row = columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }
                candidate(row)?.takeIf { it.kind != "diary" && (it.kind == "occasion" || !LocalDate.parse(it.draft.date).isBefore(today.minusDays(7))) }
                    ?.let { result += it }
            }
            result.toList()
        }
    }

    override fun mutate(mutation: CompanionCalendarMutation): Boolean {
        val target = mutation.target
        require(findCalendar() == target.calendarId)
        val base = "$eventIdentity AND ${CalendarContract.Events._ID}=?"
        val args = arrayOf(target.calendarId.toString(), account, sourceUri(target.sourceKey), target.eventId.toString())
        val row = (resolver.query(CalendarContract.Events.CONTENT_URI, columns, base, args, null)
            ?: throw IllegalStateException("系统日历查询失败")).use { cursor ->
            if (!cursor.moveToFirst()) return false
            columns.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
        if (candidate(row) != target || row[10] == CalendarContract.Events.STATUS_CANCELED.toString()) return false
        val expected = row.indices.joinToString(" AND ") { if (row[it] == null) "${columns[it]} IS NULL" else "${columns[it]}=?" }
        val expectedArgs = row.filterNotNull().toTypedArray()
        val values = if (mutation.operation == "cancel") ContentValues().apply {
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            put(CalendarContract.Events.HAS_ALARM, 0)
        } else eventValues(mutation.draft!!, target.kind, CompanionCalendarSchedule.of(mutation.draft, target.kind))
        val operations = arrayListOf(ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
            .withSelection("$base AND $expected", args + expectedArgs).withValues(values).withExpectedCount(1).build(),
            ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                .withSelection("${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(target.eventId.toString())).build())
        if (mutation.operation == "reschedule") CompanionCalendarSchedule.of(mutation.draft!!, target.kind).reminders.forEach { minutes ->
            operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValue(CalendarContract.Reminders.EVENT_ID, target.eventId)
                .withValue(CalendarContract.Reminders.MINUTES, minutes)
                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build()
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return true
    }

    private fun eventValues(draft: CalendarDraft, kind: String, schedule: CompanionCalendarSchedule): ContentValues {
        val (start, end) = CareRules.calendarTimes(draft)
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, prefix(kind) + draft.description)
            put(CalendarContract.Events.DTSTART, start)
            if (schedule.recurrence == null) put(CalendarContract.Events.DTEND, end) else putNull(CalendarContract.Events.DTEND)
            put(CalendarContract.Events.DURATION, schedule.duration); put(CalendarContract.Events.RRULE, schedule.recurrence)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC"); put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            put(CalendarContract.Events.HAS_ALARM, if (schedule.reminders.isEmpty()) 0 else 1)
        }
    }

    private fun candidate(row: List<String?>): CompanionCalendarCandidate? {
        return try {
        val key = row[3]?.removePrefix(sourcePrefix) ?: return null
        require(row[2] == account && row[3] == sourceUri(key) && row[11] == "1" && row[12] == "UTC")
        val kind = when {
            row[5].orEmpty().startsWith(prefix("plan")) -> "plan"
            row[5].orEmpty().startsWith(prefix("occasion")) -> "occasion"
            else -> return null
        }
        val epoch = row[6]?.toLongOrNull() ?: return null
        val title = row[4] ?: return null
        val description = row[5] ?: return null
        val date = Instant.ofEpochMilli(epoch).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val draft = CareRules.calendar(CalendarDraft(title, date, description.removePrefix(prefix(kind))))
        val revision = CompanionCalendarWriter.sha(row.joinToString("|") { if (it == null) "-1:" else "${it.length}:$it" })
        CompanionCalendarCandidate(key, row[0]!!.toLong(), row[1]!!.toLong(), draft, kind, revision)
        } catch (_: IllegalArgumentException) { null }
    }

    private fun prefix(kind: String): String = when (kind) { "diary" -> "[恋人日记]\n"; "occasion" -> "[纪念准备]\n"; else -> "[共同约定]\n" }
    private fun sourceUri(key: String): String { require(Regex("[a-f0-9]{64}").matches(key)); return sourcePrefix + key }
    private fun queryEvent(selection: String, arguments: Array<String>): Long? = (resolver.query(
        CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), selection, arguments, null)
        ?: throw IllegalStateException("系统日历查询失败")).use { if (it.moveToFirst()) it.getLong(0) else null }
}
