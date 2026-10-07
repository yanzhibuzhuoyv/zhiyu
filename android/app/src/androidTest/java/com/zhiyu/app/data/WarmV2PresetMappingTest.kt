package com.zhiyu.app.data

import com.zhiyu.app.domain.CompanionProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmV2PresetMappingTest {
    @Test fun currentVersionSurvivesTheActualAndroidJsonRoundTrip() {
        val warm = CompanionProfile(style = "warm_v2", name = "知心", userName = "小语")
        assertEquals(warm, JSONObject(warm.json().toString()).profile())
        assertEquals("warm_v2", warm.json().getString("style"))
    }

    @Test fun decodingDoesNotUpgradeEitherSavedPresetVersion() {
        listOf("warm_v1", "warm_v2").forEach { style ->
            val saved = CompanionProfile(style = style)
            assertEquals(saved, JSONObject(saved.json().toString()).profile())
        }
    }
}
