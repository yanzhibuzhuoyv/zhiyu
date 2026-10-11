package com.zhiyu.app.data

import android.content.ContentUris
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CalendarDraft
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Root grants calendar permission only on the isolated AVD; only this test's own event is removed. */
class CompanionCalendarProviderTest {
    @Test fun rememberedBirthdayPreparationWritesIntoOnlyTheOwnedCalendar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("companion_calendar", 0)
        val enabledBefore = preferences.getBoolean("enabled", false)
        val store = CompanionCalendarStore(context)
        assertTrue(store.allowed())
        val source = UUID.randomUUID().toString()
        val title = "生日准备测试 $source"
        var eventId: Long? = null
        try {
            store.setEnabled(true)
            val result = store.commit(source, CalendarDraft(title, "2031-03-22", "仅测试恋人自行记下的生日准备。"), "occasion")
            assertTrue(result.notice.orEmpty(), result.saved)
            eventId = result.eventId
            context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId!!),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.CUSTOM_APP_PACKAGE,
                    CalendarContract.Events.RRULE, CalendarContract.Events.DURATION, CalendarContract.Events.HAS_ALARM),
                null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(title, it.getString(0))
                assertTrue(it.getString(1).startsWith("[纪念准备]"))
                assertEquals(context.packageName, it.getString(2))
                assertEquals("FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=22", it.getString(3))
                assertEquals("P1D", it.getString(4))
                assertEquals(1, it.getInt(5))
            }
            context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use {
                assertTrue("生日需要提前准备提醒", it.moveToFirst())
                assertEquals(1440, it.getInt(0))
                assertEquals(CalendarContract.Reminders.METHOD_ALERT, it.getInt(1))
            }
        } finally {
            eventId?.let {
                context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=?",
                    arrayOf(it.toString(), title, context.packageName))
            }
            store.setEnabled(enabledBefore)
        }
    }

    @Test fun appOwnedCalendarWritesOnceWithSourceIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val preferences = context.getSharedPreferences("companion_calendar", 0)
        val enabledBefore = preferences.getBoolean("enabled", false)
        val store = CompanionCalendarStore(context)
        assertTrue("Calendar permission required on isolated test AVD", store.allowed())
        val source = UUID.randomUUID().toString()
        val title = "知语日历测试 $source"
        var eventId: Long? = null
        try {
            store.setEnabled(true)
            val draft = CalendarDraft(title, "2031-03-21", "仅测试专用日历写入和重试幂等。")
            val first = store.commit(source, draft, "plan")
            assertTrue(first.notice.orEmpty(), first.saved)
            eventId = first.eventId
            assertNotNull(eventId)
            assertEquals(eventId, store.commit(source, draft, "plan").eventId)
            resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId!!),
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.CUSTOM_APP_PACKAGE, CalendarContract.Events.DESCRIPTION),
                null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(title, it.getString(0))
                assertEquals(context.packageName, it.getString(2))
                assertTrue(it.getString(3).startsWith("[共同约定]"))
                resolver.query(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, it.getLong(1)),
                    arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
                    null, null, null)!!.use { calendar ->
                    assertTrue(calendar.moveToFirst())
                    assertEquals(context.packageName, calendar.getString(0))
                    assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, calendar.getString(1))
                }
                assertFalse(it.moveToNext())
            }
        } finally {
            eventId?.let {
                resolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE}=?",
                    arrayOf(it.toString(), title, context.packageName))
            }
            store.setEnabled(enabledBefore)
        }
    }
}
