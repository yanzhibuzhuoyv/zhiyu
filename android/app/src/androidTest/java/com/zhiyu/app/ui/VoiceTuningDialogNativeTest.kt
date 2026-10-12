package com.zhiyu.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalVoicePreferences
import com.zhiyu.app.domain.VoiceTuningSettings
import com.zhiyu.app.ui.screens.VoiceSettingsDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** The controller sees an isolated preference file; settings never alter the user's voice selection. */
class VoiceTuningDialogNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "voice-tuning-dialog-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(requested: String, mode: Int) = base.getSharedPreferences(name, mode)
    }
    @After fun cleanup() { base.deleteSharedPreferences(name) }

    @Test fun adjustedParametersSaveAndRestoreThroughTheActualDialog() {
        lateinit var controller: VoiceController
        var dismissed = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) }
            VoiceSettingsDialog(controller) { dismissed = true }
        }
        setSlider("voice_speed_slider", .85f)
        setSlider("voice_pitch_slider", -2f)
        setSlider("voice_volume_slider", .6f)
        compose.onNodeWithTag("voice_tuning_save").performClick()
        compose.waitUntil(5000) { dismissed }
        val saved = LocalVoicePreferences(context).tuning()
        assertEquals(.85f, saved.speed, .0001f)
        assertEquals(-2f, saved.pitchSemitones, .0001f)
        assertEquals(.6f, saved.volume, .0001f)
        compose.runOnIdle { assertEquals(saved, controller.tuning); controller.stop() }
    }

    @Test fun cancelKeepsPreviouslySavedTuningAndResetOnlyChangesTheDraft() {
        val original = VoiceTuningSettings(.9f, 1f, .5f)
        LocalVoicePreferences(context).saveTuning(original)
        lateinit var controller: VoiceController
        var dismissed = false
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) }
            LaunchedEffect(controller) { controller.restore() }
            VoiceSettingsDialog(controller) { dismissed = true }
        }
        compose.waitUntil(5000) { controller.tuning == original }
        compose.onNodeWithTag("voice_tuning_reset").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); controller.stop() }
        assertEquals(original, LocalVoicePreferences(context).tuning())
    }

    @Test fun nonPresetTimbreNumberSelectsAndPersistsTheActualCatalogSpeaker() {
        lateinit var controller: VoiceController
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) }
            VoiceSettingsDialog(controller) { }
        }
        compose.onNodeWithTag("voice_timbre_number").performScrollTo().performTextReplacement("174")
        compose.onNodeWithTag("voice_timbre_apply").performClick()
        compose.waitUntil(10000) { !controller.settingsSaving && controller.selectedVoiceId == 173 }
        assertEquals(173, LocalVoicePreferences(context).selected())
        compose.runOnIdle { assertEquals(VoiceTuningSettings(), controller.tuning); controller.stop() }
    }

    @Test fun linkageFailureDuringTimbreSelectionKeepsSavedParametersAndShowsAnError() {
        val original = VoiceTuningSettings(.9f, 1f, .5f)
        LocalVoicePreferences(context).select(33)
        LocalVoicePreferences(context).saveTuning(original)
        lateinit var controller: VoiceController
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) { throw UnsatisfiedLinkError("synthetic ABI failure") } }
            LaunchedEffect(controller) { controller.restore() }
            VoiceSettingsDialog(controller) { }
        }
        compose.waitUntil(5000) { controller.selectedVoiceId == 33 && controller.tuning == original }
        compose.runOnIdle { controller.selectVoice(173) }
        compose.waitUntil(5000) { !controller.settingsSaving && controller.error?.contains("本机语音无法运行") == true }
        compose.runOnIdle {
            assertEquals(33, controller.selectedVoiceId)
            assertEquals(original, controller.tuning)
            assertEquals(VoicePhase.IDLE, controller.phase)
            assertFalse(controller.busy)
            controller.stop()
        }
        assertEquals(33 to original, LocalVoicePreferences(context).snapshot())
    }

    private fun setSlider(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
}
