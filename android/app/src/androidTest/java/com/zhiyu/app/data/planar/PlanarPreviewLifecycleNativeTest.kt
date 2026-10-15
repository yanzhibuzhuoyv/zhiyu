package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.ui.screens.PlanarPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMesh
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PlanarPreviewLifecycleNativeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lifecyclePauseAndDisposalStopIdleFramesAndResumeRestoresPreview() {
        val owner = TestOwner()
        val visible = mutableStateOf(true)
        val count = AtomicInteger()
        val failure = AtomicReference<RuntimeException?>()
        val document = fixture()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) PlanarPreview(document, 0f, true, Modifier.size(96.dp), failure::set, { count.incrementAndGet() })
            }
        }
        compose.waitUntil(5_000) { count.get() >= 3 || failure.get() != null }
        assertNull(failure.get())
        assertTrue(count.get() >= 3)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        val paused = count.get()
        Thread.sleep(180)
        assertEquals("Paused preview must not keep drawing", paused, count.get())
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5_000) { count.get() > paused }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val disposed = count.get()
        Thread.sleep(180)
        assertEquals("Disposed preview must not keep drawing", disposed, count.get())
        assertNull(failure.get())
    }

    @Test fun disablingIdleDrawsOnlyWhenTheSuppliedMouthChanges() {
        val mouth = mutableStateOf(0f)
        val count = AtomicInteger()
        val last = AtomicReference<PlanarFrame?>()
        val failure = AtomicReference<RuntimeException?>()
        val document = fixture()
        compose.setContent {
            PlanarPreview(document, mouth.value, false, Modifier.size(96.dp), failure::set, {
                last.set(it); count.incrementAndGet()
            })
        }
        compose.waitUntil(5_000) { count.get() > 0 || failure.get() != null }
        assertNull(failure.get())
        compose.waitForIdle()
        // GLSurfaceView's startup/resize draws and main-thread callbacks are outside Compose's
        // idling clock. Require a bounded quiet window before checking that no idle loop exists;
        // a genuine 33ms repeating loop cannot satisfy this condition.
        var observed=count.get()
        var quietSince=SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            val latest=count.get()
            if(latest!=observed){observed=latest;quietSince=SystemClock.uptimeMillis()}
            SystemClock.uptimeMillis()-quietSince>=350
        }
        val static = count.get()
        Thread.sleep(180)
        assertEquals("Static preview must not run an idle loop", static, count.get())
        compose.runOnIdle { mouth.value = 1f }
        compose.waitUntil(5_000) { last.get()?.parameters?.get("mouth") == 1f || failure.get() != null }
        assertNull(failure.get())
        assertEquals(1f, last.get()?.parameters?.get("mouth"))
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
    private fun fixture(): PlanarDocument {
        val contract = PlanarContract(32, 16, 16, listOf("headX", "headY", "tilt", "sway", "blinkLeft", "blinkRight", "mouth", "breath")
            .associateWith { ParameterRange(if (it in setOf("headX", "headY", "tilt", "sway")) -1f else 0f, 1f, 0f) })
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val png = try {
            bitmap.eraseColor(-1)
            ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
        val mesh = PlanarMesh(contract, floatArrayOf(0f, 0f, 32f, 0f, 32f, 32f, 0f, 32f),
            floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
        return PlanarDocument(32, 32, "lifecycle", png, listOf(PlanarLayer("body", "body", 0.0, png, mesh)), "{}", contract)
    }
}
