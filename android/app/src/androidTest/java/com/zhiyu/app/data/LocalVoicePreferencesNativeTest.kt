package com.zhiyu.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

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
            assertTrue(preferences.edit().putInt("speaker", oldThird).commit())
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
}
