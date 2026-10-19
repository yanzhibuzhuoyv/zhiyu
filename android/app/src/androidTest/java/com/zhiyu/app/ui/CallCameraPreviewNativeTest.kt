package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.zhiyu.app.data.callcamera.CallCameraEngine
import com.zhiyu.app.data.callcamera.CallCameraEngineFactory
import com.zhiyu.app.ui.screens.CallCameraPreview
import com.zhiyu.app.ui.screens.LocalCallCameraEngineFactory
import com.zhiyu.app.ui.screens.LocalCallCameraScreenVisible
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Injects the engine: never asks for permission or opens a physical camera. */
class CallCameraPreviewNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pauseStopsCaptureResumeRestartsAndDisposalClosesOwnedResources() {
        val sessions = mutableListOf<FakeCamera>()
        val factory = CallCameraEngineFactory { _, _, _, front, _, _ ->
            FakeCamera(front).also { sessions.add(it) }
        }
        var show by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalCallCameraEngineFactory provides factory,
                LocalCallCameraScreenVisible provides { true }) {
                if (show) CallCameraPreview(true, {}, { fail(it) }, Modifier.size(120.dp))
            }
        }
        compose.onNodeWithTag("call_camera_preview").assertExists()
        compose.runOnIdle { assertEquals(1, sessions.size); assertTrue(sessions.single().active) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { assertFalse(sessions.single().active) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(sessions.single().active); assertEquals(2, sessions.single().starts) }
        compose.runOnIdle { show = false }
        compose.runOnIdle { assertTrue(sessions.single().closed); assertFalse(sessions.single().active) }
    }

    @Test fun lensSwitchClosesOldSessionAndStartsOnlyTheNewLens() {
        val sessions = mutableListOf<FakeCamera>()
        val factory = CallCameraEngineFactory { _, _, _, front, _, _ ->
            FakeCamera(front).also { sessions.add(it) }
        }
        var front by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalCallCameraEngineFactory provides factory,
                LocalCallCameraScreenVisible provides { true }) {
                CallCameraPreview(front, {}, { fail(it) }, Modifier.size(120.dp))
            }
        }
        compose.runOnIdle { assertTrue(sessions.single().front); front = false }
        compose.runOnIdle {
            assertEquals(2, sessions.size)
            assertTrue(sessions[0].closed)
            assertFalse(sessions[0].active)
            assertFalse(sessions[1].front)
            assertTrue(sessions[1].active)
        }
    }

    @Test fun screenLockedDoesNotStartTheCameraEvenWhileTheActivityIsResumed() {
        val sessions = mutableListOf<FakeCamera>()
        val factory = CallCameraEngineFactory { _, _, _, front, _, _ ->
            FakeCamera(front).also { sessions.add(it) }
        }
        compose.setContent {
            CompositionLocalProvider(LocalCallCameraEngineFactory provides factory,
                LocalCallCameraScreenVisible provides { false }) {
                CallCameraPreview(true, {}, { fail(it) }, Modifier.size(120.dp))
            }
        }
        compose.runOnIdle { assertEquals(1, sessions.size); assertFalse(sessions.single().active) }
    }

    private class FakeCamera(val front: Boolean) : CallCameraEngine {
        var active = false
        var closed = false
        var starts = 0
        override fun start() { if (!closed && !active) { active = true; starts++ } }
        override fun stop() { active = false }
        override fun close() { stop(); closed = true }
    }
}
