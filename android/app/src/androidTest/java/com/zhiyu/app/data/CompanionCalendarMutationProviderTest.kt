package com.zhiyu.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CalendarDraft
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** Synthetic events on the root-owned test AVD only. Cleanup always binds exact IDs and source identity. */
class CompanionCalendarMutationProviderTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun reply(store: CompanionCalendarStore, source: String, title: String, old: String, next: String?): CompanionCalendarReply {
        val kind = if (next == null) "cancel" else "reschedule"
        val user = if (next == null) "我们$old 看电影的约定取消了。" else "我们$old 看电影的约定改到$next。"
        val plan = store.promptPlan(user)
        val target = plan.candidates.single { it.sourceKey == CompanionCalendarWriter.sha(source) }
        val json = """{"kind":"$kind","target":"${target.sourceKey}","title":"$title","date":"${next ?: old}","description":"","evidence":"$user"}"""
        return CompanionCalendarProtocol.parse("我听你的。\n[[ZHIYU_CALENDAR_${plan.nonce}:$json]]", plan).also { assertNotNull(it.mutation) }
    }
    private fun cleanup(eventId: Long?, source: String) {
        if (eventId == null) return
        context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=?",
            arrayOf(eventId.toString(), context.packageName, "zhiyu://companion-calendar/${CompanionCalendarWriter.sha(source)}"))
    }
    private fun reminders(eventId: Long): List<Int> = context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
        arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use {
        buildList { while (it.moveToNext()) add(it.getInt(0)) }
    }

    @Test fun confirmedRescheduleChangesSameEventAndReminderWhileStaleSnapshotCannotUndoIt() {
        val store = CompanionCalendarStore(context)
        val enabled = context.getSharedPreferences("companion_calendar", 0).getBoolean("enabled", false)
        val source = UUID.randomUUID().toString(); val title = "一起看电影 $source"
        val old = LocalDate.now().plusDays(2).toString(); val next = LocalDate.now().plusDays(4).toString()
        var eventId: Long? = null
        try {
            assertTrue(store.allowed()); store.setEnabled(true)
            eventId = store.commit(source, CalendarDraft(title, old, "原定安排"), "plan").eventId!!
            val change = reply(store, source, title, old, next)
            val staleCancel = reply(store, source, title, old, null)
            assertTrue(store.commit("move_${UUID.randomUUID()}", change).saved)
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.RRULE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(com.zhiyu.app.domain.CareRules.calendarTimes(CalendarDraft(title, next, "")).first, it.getLong(0))
                assertTrue(it.isNull(1))
            }
            assertEquals(listOf(60), reminders(eventId))
            assertFalse(store.commit("stale_${UUID.randomUUID()}", staleCancel).saved)
            assertEquals(eventId, store.commit(source, CalendarDraft(title, old, "原定安排"), "plan").eventId)
        } finally { cleanup(eventId, source); store.setEnabled(enabled) }
    }

    @Test fun cancellationStopsRemindersDoesNotReviveOnReplayAndLeavesUserEventUntouched() {
        val store = CompanionCalendarStore(context)
        val enabled = context.getSharedPreferences("companion_calendar", 0).getBoolean("enabled", false)
        val source = UUID.randomUUID().toString(); val title = "一起看电影 $source"
        val date = LocalDate.now().plusDays(2).toString()
        var eventId: Long? = null; var manualId: Long? = null
        try {
            assertTrue(store.allowed()); store.setEnabled(true)
            eventId = store.commit(source, CalendarDraft(title, date, "原定安排"), "plan").eventId!!
            val calendar = store.promptPlan("$date 看电影取消了。").candidates.single { it.eventId == eventId }.calendarId
            val times = com.zhiyu.app.domain.CareRules.calendarTimes(CalendarDraft(title, date, ""))
            val userValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar); put(CalendarContract.Events.TITLE, "用户自建 $source")
                put(CalendarContract.Events.DTSTART, times.first); put(CalendarContract.Events.DTEND, times.second)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC"); put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            }
            manualId = ContentUris.parseId(context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, userValues)!!)
            assertTrue(store.commit("cancel_${UUID.randomUUID()}", reply(store, source, title, date, null)).saved)
            assertTrue(reminders(eventId).isEmpty())
            val restarted = CompanionCalendarStore(context)
            assertEquals(eventId, restarted.commit(source, CalendarDraft(title, date, "原定安排"), "plan").eventId)
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.STATUS, CalendarContract.Events.HAS_ALARM), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(CalendarContract.Events.STATUS_CANCELED, it.getInt(0)); assertEquals(0, it.getInt(1))
            }
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, manualId),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.STATUS), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("用户自建 $source", it.getString(0)); assertEquals(CalendarContract.Events.STATUS_CONFIRMED, it.getInt(1))
            }
        } finally {
            cleanup(eventId, source)
            manualId?.let { context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} IS NULL",
                arrayOf(it.toString(), "用户自建 $source")) }
            store.setEnabled(enabled)
        }
    }

    @Test fun userEditingTheEventDuringGenerationPreventsOverwriteAndKeepsReminder() {
        val store = CompanionCalendarStore(context)
        val enabled = context.getSharedPreferences("companion_calendar", 0).getBoolean("enabled", false)
        val source = UUID.randomUUID().toString(); val title = "一起看电影 $source"
        val old = LocalDate.now().plusDays(2).toString(); val next = LocalDate.now().plusDays(4).toString()
        var eventId: Long? = null
        try {
            assertTrue(store.allowed()); store.setEnabled(true)
            eventId = store.commit(source, CalendarDraft(title, old, "原定安排"), "plan").eventId!!
            val change = reply(store, source, title, old, next)
            context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                ContentValues().apply { put(CalendarContract.Events.TITLE, "用户改过 $source") }, null, null)
            assertFalse(store.commit("move_${UUID.randomUUID()}", change).saved)
            assertEquals(listOf(60), reminders(eventId))
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events.TITLE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("用户改过 $source", it.getString(0))
            }
        } finally { cleanup(eventId, source); store.setEnabled(enabled) }
    }
}
