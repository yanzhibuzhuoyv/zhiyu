package com.zhiyu.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalVoicePreferences
import com.zhiyu.app.ui.screens.VoiceSettingsDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class VoiceGenderDialogNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "voice-gender-dialog-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(requested: String, mode: Int) = base.getSharedPreferences(name, mode)
    }
    private lateinit var controller: VoiceController
    @After fun cleanup() {
        if (::controller.isInitialized) compose.runOnUiThread { controller.stop() }
        base.deleteSharedPreferences(name)
    }

    @Test fun maleAndFemaleCatalogsAreSeparateAndBrowsingDoesNotChangeTheSavedVoice() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) { 174 } }
            VoiceSettingsDialog(controller) {}
        }
        compose.onNodeWithTag("voice_gender_MALE").assertExists()
        compose.onNodeWithTag("voice_gender_FEMALE").performClick()
        compose.onNodeWithTag("voice_catalog_menu").performClick()
        compose.onNodeWithTag("voice_catalog_0").assertExists()
        compose.onNodeWithTag("voice_catalog_10").assertDoesNotExist()
        assertEquals(10, controller.selectedVoiceId)
    }

    @Test fun selectionFromFemaleCatalogPersistsTheActualSpeakerAndUpdatesItsGroupLabel() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember { VoiceController(context, scope) { 174 } }
            VoiceSettingsDialog(controller) {}
        }
        compose.onNodeWithTag("voice_gender_FEMALE").performClick()
        compose.onNodeWithTag("voice_catalog_menu").performClick()
        compose.onNodeWithTag("voice_catalog_0").performClick()
        compose.waitUntil(5000) { !controller.settingsSaving && controller.selectedVoiceId == 0 }
        assertEquals(0, LocalVoicePreferences(context).selected())
        compose.onNodeWithTag("voice_selected_label").assertTextEquals("当前：女声 · 音色 1")
    }
}
