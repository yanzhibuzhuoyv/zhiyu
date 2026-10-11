package com.zhiyu.app.data

import android.content.Intent
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class CareFeaturesTest {
    private val context = CareTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun cleanup() { context.cleanup() }
    private val now = 1_800_000_000L
    private fun valid(): JSONObject = JSONObject()
        .put("current_units", JSONObject().put("temperature_2m", "°C").put("apparent_temperature", "°C")
            .put("precipitation", "mm").put("wind_speed_10m", "km/h").put("time", "unixtime"))
        .put("current", JSONObject().put("time", now - 600).put("temperature_2m", 21.3).put("apparent_temperature", 20.0)
            .put("weather_code", 61).put("precipitation", 0.4).put("wind_speed_10m", 12.1))

    @Test fun weatherParserAcceptsBoundedRealNumbersAndUtcTime() {
        val snapshot = NativeWeatherClient().decode(valid().toString(), now)
        assertEquals(21.3, snapshot.temperatureC, 0.01)
        assertEquals(61, snapshot.weatherCode)
        assertEquals(now - 600, snapshot.observedAt)
        assertEquals(now, snapshot.fetchedAt)
    }

    @Test fun weatherParserRejectsMissingFieldsAndNumericStrings() {
        val missing = valid().also { it.getJSONObject("current").remove("temperature_2m") }
        val coerced = valid().also { it.getJSONObject("current").put("temperature_2m", "21.3") }
        listOf(missing, coerced).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeWeatherClient().decode(it.toString(), now) }
        }
    }

    @Test fun weatherParserRejectsWrongUnitsFractionalCodesAndStaleTime() {
        val wrongUnits = valid().also { it.getJSONObject("current_units").put("temperature_2m", "°F") }
        val fractional = valid().also { it.getJSONObject("current").put("weather_code", 61.5) }
        val stale = valid().also { it.getJSONObject("current").put("time", now - 7201) }
        val future = valid().also { it.getJSONObject("current").put("time", now + 901) }
        listOf(wrongUnits, fractional, stale, future).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeWeatherClient().decode(it.toString(), now) }
        }
    }

    @Test fun preferencesStoreOnlyWeatherFactsAndClearThemWhenDisabled() {
        // UUID-scoped preferences under the executing UID; no application credentials are read.
        val store = CarePreferences(context)
        val snapshot = NativeWeatherClient().decode(valid().toString(), now)
        try {
            store.save(CarePreferenceState(true, snapshot))
            assertEquals(CarePreferenceState(true, snapshot), store.load().state)
            val keys = context.getSharedPreferences("weather_care", 0).all.keys
            assertFalse(keys.any { it.contains("latitude") || it.contains("longitude") || it.contains("location") })
            store.save(CarePreferenceState(false, snapshot))
            assertEquals(CarePreferenceState(false), store.load().state)
            assertEquals(setOf("enabled"), context.getSharedPreferences("weather_care", 0).all.keys)
        } finally { store.save(CarePreferenceState()) }
    }

    @Test fun corruptPreferencesDoNotProduceWeatherFactsOrCrashTheApp() {
        val preferences = context.getSharedPreferences("weather_care", 0)
        try {
            assertTrue(preferences.edit().clear().putBoolean("enabled", true).putString("observed_at", "wrong type").commit())
            val loaded = CarePreferences(context).load()
            assertNotNull(loaded.warning)
            assertFalse(loaded.state.enabled)
            assertNull(loaded.state.weather)
        } finally { CarePreferences(context).save(CarePreferenceState()) }
    }

    @Test fun calendarHandOffIsAnEditableInsertAndDoesNotPretendItHasSaved() {
        val intent = CalendarCareActions.insert(CalendarDraft("纪念日", "2026-10-09", "今天很开心。"))
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("纪念日", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("今天很开心。", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertTrue(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertEquals(86_400_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0) -
            intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertFalse(intent.hasExtra("calendar_id"))
    }
}
