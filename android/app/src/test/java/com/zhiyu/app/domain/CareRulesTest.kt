package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CareRulesTest {
    private val now = 1_800_000_000L
    private val weather = WeatherSnapshot(now - 600, now, 21.3, 20.0, 61, 0.4, 12.1)

    @Test fun coordinatesAreBoundedFiniteAndRoundedBeforeTheyLeaveTheDevice() {
        val point = CareRules.approximate(ApproximateLocation(31.2345, 121.487))
        assertEquals(31.2, point.latitude, 0.0001)
        assertEquals(121.5, point.longitude, 0.0001)
        listOf(ApproximateLocation(Double.NaN, 0.0), ApproximateLocation(0.0, Double.POSITIVE_INFINITY),
            ApproximateLocation(91.0, 0.0), ApproximateLocation(0.0, -181.0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CareRules.approximate(it) }
        }
        assertFalse(point.toString().contains("31.2"))
    }

    @Test fun weatherUsesOnlyTheFixedHttpsEndpointAndBoundedFields() {
        val url = CareRules.weatherUrl(ApproximateLocation(31.234, 121.487))
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=31.2"))
        assertTrue(url.contains("longitude=121.5"))
        assertTrue(url.contains("timeformat=unixtime"))
        assertTrue(url.contains("temperature_unit=celsius"))
        assertTrue(url.contains("wind_speed_unit=kmh"))
        assertFalse(url.contains("apikey"))
    }

    @Test fun weatherFactsNeverGrantMonitoringOrRewriteTheBoundPersonality() {
        val context = CareRules.prompt(CarePreferenceState(true, weather), now, true)
        assertTrue(context.contains("21.3"))
        assertTrue(context.contains("小雨"))
        assertTrue(context.contains("已经设定的性格"))
        assertTrue(context.contains("不要每条回复"))
        assertTrue(context.contains("不能当作用户亲口分享"))
        assertTrue(context.contains("不拥有持续定位"))
        assertFalse(context.contains("121.5"))
    }

    @Test fun disabledRevokedStaleAndFutureWeatherProvideNoPrompt() {
        assertEquals("", CareRules.prompt(CarePreferenceState(false, weather), now, true))
        assertEquals("", CareRules.prompt(CarePreferenceState(true, weather), now, false))
        assertEquals("", CareRules.prompt(CarePreferenceState(true), now, true))
        assertEquals("", CareRules.prompt(CarePreferenceState(true, weather), now + 7201, true))
        assertEquals("", CareRules.prompt(CarePreferenceState(true, weather.copy(observedAt = now + 901)), now, true))
        assertEquals("", CareRules.prompt(CarePreferenceState(true, weather.copy(fetchedAt = now + 301)), now, true))
    }

    @Test fun malformedWeatherIsRejectedInsteadOfInventingConditions() {
        listOf(weather.copy(temperatureC = Double.NaN), weather.copy(feelsLikeC = 140.0),
            weather.copy(weatherCode = 123), weather.copy(precipitationMm = -1.0),
            weather.copy(windSpeedKmh = Double.POSITIVE_INFINITY), weather.copy(observedAt = 0L)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CareRules.weather(it) }
        }
        assertEquals(weather, CareRules.weather(weather))
    }

    @Test fun calendarDraftUsesExactDatesAndAllDayUtcBoundaries() {
        val draft = CareRules.calendar(CalendarDraft("  第一次见面  ", "2026-10-09", " 今天留个纪念。 "))
        assertEquals("第一次见面", draft.title)
        assertEquals("今天留个纪念。", draft.description)
        val event = CareRules.calendarTimes(draft)
        assertEquals(LocalDate.of(2026, 10, 9).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), event.first)
        assertEquals(86_400_000L, event.second - event.first)
    }

    @Test fun calendarRejectsImpossibleDatesBlankTitlesAndControlCharacters() {
        listOf(CalendarDraft("  ", "2026-10-09", ""), CalendarDraft("日记", "2026-02-30", ""),
            CalendarDraft("日记", "2026-2-1", ""), CalendarDraft("日记\u0000", "2026-10-09", ""),
            CalendarDraft("日记", "2026-10-09", "x".repeat(2001))).forEach {
            assertThrows(IllegalArgumentException::class.java) { CareRules.calendar(it) }
        }
        assertEquals("生日", CareRules.calendar(CalendarDraft("生日", "2028-02-29", "第一行\n第二行")).title)
    }
}
