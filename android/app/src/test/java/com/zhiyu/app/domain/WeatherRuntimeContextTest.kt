package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class WeatherRuntimeContextTest {
    private val now = 1792368000L
    private val weather = WeatherSnapshot(now, now, 12.0, 10.0, 61, 0.2, 9.0)
    @Test fun permissionAndStalenessAreRealStatesRatherThanAnEmptyContext() {
        assertTrue(WeatherRuntimeContext.render(CarePreferenceState(true, weather), now, false).contains("weather_state=permission_missing"))
        assertTrue(WeatherRuntimeContext.render(CarePreferenceState(false), now, true).contains("weather_state=disabled"))
        val waiting = WeatherRuntimeContext.render(CarePreferenceState(true), now, true)
        assertTrue(waiting.contains("weather_state=awaiting_fix")); assertFalse(waiting.contains("12.0"))
        val stale = WeatherRuntimeContext.render(CarePreferenceState(true, weather), now + 7201, true)
        assertTrue(stale.contains("weather_state=stale")); assertFalse(stale.contains("12.0"))
    }
    @Test fun freshFactsAreBoundedAndAvailableWithoutInventingLocation() {
        val ready = WeatherRuntimeContext.render(CarePreferenceState(true, weather), now, true)
        assertTrue(ready.contains("weather_state=ready")); assertTrue(ready.contains("小雨")); assertTrue(ready.contains("12.0"))
        assertFalse(ready.contains("latitude")); assertFalse(ready.contains("longitude"))
    }
    @Test fun onlyActualWeatherQuestionsRequestAnImmediateRefresh() {
        listOf("我这边天气怎么样", "外面下雨了吗", "今天多少度", "出门要带伞吗").forEach { assertTrue(it, WeatherRuntimeContext.requested(it)) }
        listOf("昨天那场雨真大", "给我讲个雨天的故事", "我叫天气", "我想买本书").forEach { assertFalse(it, WeatherRuntimeContext.requested(it)) }
    }
}
