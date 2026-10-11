package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import com.zhiyu.app.ui.screens.VoiceSettingsDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceLifecycleNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun globalBackgroundLifecycleStopsPreviewOutsideTheCallDialog() {
        lateinit var controller: VoiceController
        compose.setContent { controller = rememberVoiceController() }
        compose.runOnIdle {
            controller.preview(10)
            assertTrue(controller.busy)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            assertEquals(VoicePhase.IDLE, controller.phase)
            assertFalse(controller.busy)
            assertEquals(0f, controller.mouthLevel, 0f)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(VoicePhase.IDLE, controller.phase) }
    }

    @Test fun settingsCloseStopsPreviewEvenWhenTheParentKeepsTheDialogComposed() {
        lateinit var controller: VoiceController
        var dismissed = false
        compose.setContent {
            controller = rememberVoiceController()
            VoiceSettingsDialog(controller) { dismissed = true }
        }
        compose.runOnIdle { controller.preview(33); assertTrue(controller.busy) }
        compose.onNodeWithText("好了").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(VoicePhase.IDLE, controller.phase)
            assertFalse(controller.busy)
        }
    }
}
