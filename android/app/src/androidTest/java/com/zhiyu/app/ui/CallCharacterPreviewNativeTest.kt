package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.domain.planar.DetectedFaceRegion
import com.zhiyu.app.ui.screens.CallCharacterPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CallCharacterPreviewNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun identity(id: Char) = OcCallCharacterRules.resolve(null, OcAssetRef(id.toString().repeat(32), "a".repeat(64), 1))!!
    private fun photo(color: Int = Color.CYAN) = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun openingImportedPhotoPreparesAndActuallyDrawsLocalIdleMotionWithoutAnEditorVisit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contract = PlanarPackageReader(context.assets.open("planar/contract.json").bufferedReader().use { it.readText() }).contract
        val frames = CopyOnWriteArrayList<PlanarFrame>()
        val character = identity('a')
        compose.setContent {
            CallCharacterPreview(character, null, .7f, { photo() }, {}, Modifier.fillMaxSize(),
                prepare = { source -> AutomaticCallPlanarBuilder(contract) {
                    listOf(DetectedFaceRegion(160.0, 125.0, 40.0, .93))
                }.build(source) }, onFrame = { frames.add(it) })
        }
        compose.waitUntil(10000) { frames.size >= 6 }
        compose.onNodeWithTag("call_character_animated").assertExists()
        assertTrue(frames.map { it.parameters.getValue("headX") }.distinct().size > 1)
        compose.onNodeWithTag("call_character_edit").assertExists()
    }

    @Test fun unrecognizedDrawingKeepsOriginalVisibleAndProvidesADirectManualFix() {
        var edits = 0
        val character = identity('a')
        compose.setContent { CallCharacterPreview(character, null, 0f, { photo() }, { edits++ },
            Modifier.fillMaxSize(), prepare = { null }) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("call_character_still").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("标注人物，让 TA 动起来").assertExists()
        compose.onNodeWithTag("call_character_edit").performClick()
        assertEquals(1, edits)
    }

    @Test fun latePreparationForAnOldPhotoCannotReplaceTheNewIdentityOrRecycleItsDisplay() {
        val gate = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val oldPhoto = AtomicReference<Bitmap>()
        var current by mutableStateOf(identity('a'))
        compose.setContent { CallCharacterPreview(current, null, 0f, { id ->
            loads.incrementAndGet()
            photo(if (id.ocId.startsWith("a")) Color.RED else Color.BLUE).also {
                if (id.ocId.startsWith("a")) oldPhoto.set(it)
            }
        }, {}, Modifier.fillMaxSize(), prepare = { source ->
            if (source.getPixel(0, 0) == Color.RED) withContext(NonCancellable) { gate.await() }
            null
        }) }
        try {
            compose.waitUntil(5000) { oldPhoto.get() != null }
            compose.runOnIdle { current = identity('b') }
            compose.waitUntil(5000) { loads.get() == 2 && compose.onAllNodesWithTag("call_character_still").fetchSemanticsNodes().size == 1 }
            gate.complete(Unit)
            compose.waitUntil(5000) { oldPhoto.get().isRecycled }
            val pixels = compose.onNodeWithTag("call_character_still").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertEquals(1f, center.blue, .01f); assertEquals(0f, center.red, .01f)
        } finally { gate.complete(Unit) }
    }
}
