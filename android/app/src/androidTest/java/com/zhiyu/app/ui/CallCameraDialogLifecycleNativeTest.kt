package com.zhiyu.app.ui

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.callcamera.CallCameraEngine
import com.zhiyu.app.data.callcamera.CallCameraEngineFactory
import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.ui.screens.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import rig2d.voice.DuplexPortsFactory
import java.time.Instant

/** Tests real dialog ownership with injected cameras. No physical capture, network or user data. */
class CallCameraDialogLifecycleNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = CallCameraSession()
    private val engines = mutableListOf<FakeCamera>()
    private val controller = DuplexVoiceController(scope, { _, _ ->
        DuplexPortsFactory { error("This fixture never starts audio") }
    })

    @Before fun grantCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} ${Manifest.permission.CAMERA}"
        )).use { it.readBytes() }
    }

    @After fun cleanup() {
        compose.runOnUiThread { controller.dispose(); session.clear() }
        scope.cancel()
    }

    private fun open() {
        val factory = CallCameraEngineFactory { _, _, _, _, frame, error ->
            FakeCamera(frame, error).also(engines::add)
        }
        compose.setContent {
            CompositionLocalProvider(LocalCallCameraEngineFactory provides factory,
                LocalCallCameraScreenVisible provides { true }) {
                DuplexCallDialog(controller, "a".repeat(32), "测试角色", { _, _ -> error("No chat") }, {},
                    cameraSession = session, visualSupported = true)
            }
        }
        compose.onNodeWithTag("duplex_camera_toggle").performClick()
        compose.runOnIdle { assertEquals(1, engines.size); engines.single().emit(); assertNotNull(session.latest()) }
    }

    @Test fun pauseWithoutStopInvalidatesPixelsAndCancelsPendingVision() {
        open()
        lateinit var pending: Job
        compose.runOnIdle {
            val lease = session.latest()!!
            pending = scope.launch { session.withLease(lease) { awaitCancellation() } }
            assertFalse(pending.isCompleted)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity {
            assertNull("ON_PAUSE must invalidate pixels even without ON_STOP", session.latest())
            assertTrue("Pausing must cancel the pending visual request", pending.isCancelled)
        }
    }

    @Test fun oldLensCallbackBetweenClickAndRecompositionCannotEnterNewGeneration() {
        open()
        val old = engines.single()
        val switch = compose.onNodeWithTag("duplex_camera_switch").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            assertTrue(switch())
            // A queued old-engine callback runs before Compose disposes that engine.
            old.emit()
            assertNull("Old pixels must not receive the new camera token", session.latest())
        }
        compose.runOnIdle {
            assertTrue(old.closed)
            assertEquals(2, engines.size)
            engines.last().emit()
            assertNotNull(session.latest())
        }
    }

    @Test fun captureFailureInvalidatesPreviouslyAvailablePixels() {
        open()
        compose.runOnUiThread {
            engines.single().error("测试摄像头读取失败")
            assertNull("An unavailable camera must not keep serving its last frame", session.latest())
        }
        compose.onNodeWithText("测试摄像头读取失败").assertExists()
    }

    private class FakeCamera(val frame: (CallCameraFrame) -> Unit, val error: (String) -> Unit) : CallCameraEngine {
        var closed = false
        override fun start() = Unit
        override fun stop() = Unit
        override fun close() { closed = true }
        fun emit() {
            val bytes = byteArrayOf(-1, -40, -1, 7)
            val hash = ChatPhotoRules.sha(bytes)
            frame(CallCameraFrame(ChatPhotoPayload(hash, hash, "image/jpeg", bytes),
                Instant.now(), SystemClock.elapsedRealtime()))
        }
    }
}
