package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import com.zhiyu.app.domain.VoiceTuningSettings

/** Isolated synthetic voice settings only; never opens credentials or the real voice preferences. */
class LocalVoicePreferencesNativeTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "voice-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(requested: String, mode: Int) = base.getSharedPreferences(name, mode)
    }
    private val preferences get() = context.getSharedPreferences("zhiyu_local_voice", Context.MODE_PRIVATE)
    @After fun cleanup() { base.deleteSharedPreferences(name) }

    @Test fun replacedThirdVoiceIsPersistentlyMigratedWithoutChangingOtherChosenVoices() {
        listOf(99, 2).forEach { oldThird ->
            assertTrue(preferences.edit().putInt("speaker", oldThird).putBoolean("speaker_catalog_v1", false).commit())
            assertEquals(0, LocalVoicePreferences(context).selected())
            assertEquals(0, preferences.getInt("speaker", -1))
            assertEquals(0, LocalVoicePreferences(context).selected())
        }
        LocalVoicePreferences(context).select(10)
        assertEquals(10, LocalVoicePreferences(context).selected())
        LocalVoicePreferences(context).select(33)
        assertEquals(33, LocalVoicePreferences(context).selected())
        assertEquals(33, preferences.getInt("speaker", -1))
    }

    @Test fun unknownPersistedSpeakerIsRejectedAndNotSilentlyRewritten() {
        assertTrue(preferences.edit().putInt("speaker", 999).commit())
        try { LocalVoicePreferences(context).selected(); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(999, preferences.getInt("speaker", -1))
    }

    @Test fun oldInstallsKeepOriginalParametersAndTuningPersistsAcrossNewInstances() {
        assertEquals(VoiceTuningSettings(), LocalVoicePreferences(context).tuning())
        val desired = VoiceTuningSettings(.8f, -2.5f, .65f)
        LocalVoicePreferences(context).select(33)
        LocalVoicePreferences(context).saveTuning(desired)
        assertEquals(33 to desired, LocalVoicePreferences(context).snapshot())
        LocalVoicePreferences(context).select(10)
        assertEquals(10 to desired, LocalVoicePreferences(context).snapshot())
    }

    @Test fun corruptPersistedParametersAreRejectedWithoutSilentOverwrite() {
        assertTrue(preferences.edit().putFloat("speed", Float.NaN).putFloat("pitch_semitones", 999f).commit())
        try { LocalVoicePreferences(context).tuning(); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(preferences.getFloat("speed", 1f).isNaN())
        assertEquals(999f, preferences.getFloat("pitch_semitones", 0f), 0f)
        LocalVoicePreferences(context).saveTuning(VoiceTuningSettings())
        assertEquals(VoiceTuningSettings(), LocalVoicePreferences(context).tuning())
    }

    @Test fun explicitCatalogSelectionsNeverTriggerLegacyThirdVoiceMigration() {
        listOf(1,2,99,173).forEach { id ->
            LocalVoicePreferences(context).select(id)
            assertEquals(id, LocalVoicePreferences(context).selected())
            assertTrue(preferences.getBoolean("speaker_catalog_v1", false))
        }
        listOf(-1,174,999).forEach { id ->
            try { LocalVoicePreferences(context).select(id); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(173, LocalVoicePreferences(context).selected())
        }
    }
}
