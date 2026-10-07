package com.zhiyu.app.data

import com.zhiyu.app.domain.CompanionProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmPresetMappingTest {
    @Test fun newVersionSurvivesTheActualAndroidJsonRoundTrip() {
        val warm = CompanionProfile(style = "warm_v1", name = "知心", userName = "小语")
        assertEquals(warm, JSONObject(warm.json().toString()).profile())
        assertEquals("warm_v1", warm.json().getString("style"))
    }

    @Test fun legacyJsonWithoutStyleKeepsTheOriginalGentleDefault() {
        assertEquals("gentle", JSONObject().put("mode", "preset").profile().style)
        listOf("gentle", "playful", "steady").forEach { style ->
            val legacy = CompanionProfile(style = style)
            assertEquals(legacy, JSONObject(legacy.json().toString()).profile())
        }
    }
}
