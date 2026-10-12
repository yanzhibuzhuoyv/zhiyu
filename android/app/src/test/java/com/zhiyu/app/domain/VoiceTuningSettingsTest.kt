package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class VoiceTuningSettingsTest {
    @Test fun defaultsKeepTheExistingDurationPitchAndGain() {
        val value = VoiceTuningSettings()
        assertEquals(1f, value.speed, 0f)
        assertEquals(0f, value.pitchSemitones, 0f)
        assertEquals(1f, value.pitchRatio, 0f)
        assertEquals(1f, value.volume, 0f)
    }

    @Test fun pitchUsesMusicalSemitonesIndependentlyOfDuration() {
        val lower = VoiceTuningSettings(speed = 1.35f, pitchSemitones = -4f)
        val higher = VoiceTuningSettings(speed = .75f, pitchSemitones = 4f)
        assertEquals(.7937005f, lower.pitchRatio, .000001f)
        assertEquals(1.259921f, higher.pitchRatio, .000001f)
        assertEquals(1.35f, lower.speed, 0f)
        assertEquals(.75f, higher.speed, 0f)
    }

    @Test fun changesAreIndependentImmutableValues() {
        val original = VoiceTuningSettings()
        val edited = original.copy(speed = .8f, volume = .4f)
        assertEquals(1f, original.speed, 0f)
        assertEquals(1f, original.volume, 0f)
        assertEquals(.8f, edited.speed, 0f)
        assertEquals(1f, edited.pitchRatio, 0f)
    }

    @Test fun invalidExternalSettingsNeverReachTheNativeOrPlaybackApis() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, .74f, 1.36f).forEach {
            rejected { VoiceTuningSettings(speed = it) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -4.01f, 4.01f).forEach {
            rejected { VoiceTuningSettings(pitchSemitones = it) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f).forEach {
            rejected { VoiceTuningSettings(volume = it) }
        }
        assertEquals(0f, VoiceTuningSettings(volume = 0f).volume, 0f)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid settings were accepted") } catch (_: IllegalArgumentException) { }
    }
}
