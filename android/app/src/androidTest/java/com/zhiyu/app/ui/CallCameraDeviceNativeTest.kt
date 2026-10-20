package com.zhiyu.app.ui

import android.Manifest
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.CallCameraFrameRules
import com.zhiyu.app.ui.screens.CallCameraPreview
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

/** Run on the dedicated emulator configured with emulated front/back cameras, never a user phone. */
class CallCameraDeviceNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun grant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic camera acceptance requires a dedicated emulator"
        }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${instrumentation.targetContext.packageName} ${Manifest.permission.CAMERA}"
        )).use { it.readBytes() }
    }

    @Test fun realCameraXBindingEncodesBothEmulatedLensesAndCanReopen() {
        var front by mutableStateOf(true)
        var visible by mutableStateOf(true)
        val frame = AtomicReference<CallCameraFrame?>()
        val error = AtomicReference<String?>()
        compose.setContent {
            if (visible) CallCameraPreview(front, { frame.set(it) }, { error.set(it) }, Modifier.size(160.dp))
        }
        fun awaitImage(): CallCameraFrame {
            compose.waitUntil(20_000) { frame.get() != null || error.get() != null }
            assertNull("Real CameraX binding reported an error", error.get())
            val result = checkNotNull(frame.get())
            assertTrue(result.photo.byteCount in 1..CallCameraFrameRules.MAX_BYTES)
            val decoded = BitmapFactory.decodeByteArray(result.photo.bytes, 0, result.photo.byteCount)
            assertNotNull(decoded)
            try { assertTrue(decoded.width in 1..768); assertTrue(decoded.height in 1..768) }
            finally { decoded.recycle() }
            return result
        }
        val first = awaitImage()
        compose.runOnIdle { front = false; frame.set(null) }
        compose.waitForIdle()
        // Discard any pre-switch sample delivered before the old effect closed.
        compose.runOnIdle { frame.set(null) }
        val back = awaitImage()
        assertNotSame(first, back)
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { frame.set(null); visible = true }
        assertNotSame(back, awaitImage())
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
    }
}
