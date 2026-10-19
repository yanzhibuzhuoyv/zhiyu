package com.zhiyu.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.domain.OcAssetRef
import com.zhiyu.app.domain.OcCallCharacterRules
import com.zhiyu.app.domain.planar.*
import com.zhiyu.app.ui.screens.CallCharacterPreview
import com.zhiyu.app.ui.screens.PlanarWorkshopDialog
import com.zhiyu.app.ui.theme.ZhiYuTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Actual editor/call windows, source-sized portrait, visible marker travel in dp, and fixed background. */
// WindowInspector requires API29+. This class is run on the supported QA emulator; the
// runner contains SdkSuppress at runtime but is not an androidTest compile dependency.
@SuppressLint("UseSdkSuppress")
@RequiresApi(29)
class PlanarVisibleMotionNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = NativeV4Context(base)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller = PlanarWorkshopController(context, scope)
    private val binding = "visible-motion:" + "a".repeat(64)

    @Before fun requireTheApiForInspectingActualPreviewWindows() {
        assumeTrue("WindowInspector requires API29+", Build.VERSION.SDK_INT >= 29)
    }

    @After fun cleanup() {
        compose.runOnUiThread { controller.dispose() }
        runBlocking { controller.awaitCheckpoint() }
        scope.cancel(); context.close()
        val root = context.filesDir.canonicalFile
        check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
        check(root.deleteRecursively())
    }

    private fun prepare(strength: Double = 1.0, compactHead: Boolean = false) {
        compose.runOnIdle { controller.connect(binding) }
        compose.waitUntil(5000) { !controller.busy }
        compose.runOnIdle { controller.open { portrait() } }
        compose.waitUntil(5000) { controller.draft != null && !controller.busy }
        compose.runOnIdle {
            val draft = controller.draft!!
            val polygon = if (compactHead) listOf(PlanarPoint(657.0, 171.0), PlanarPoint(837.0, 171.0),
                PlanarPoint(837.0, 406.0), PlanarPoint(657.0, 406.0))
                else listOf(PlanarPoint(500.0, 80.0), PlanarPoint(1000.0, 80.0),
                    PlanarPoint(1000.0, 520.0), PlanarPoint(500.0, 520.0))
            // Constructor default is the actual head-suggestion default. A converted accessory
            // retains the editor's .5 strength, and zero must continue to mean no head motion.
            val head = PlanarPart("manual_head", "手画头部", "face", 20.0, polygon, PlanarPoint(750.0, 280.0))
            controller.edit(draft.copy(parts = listOf(if (strength == 1.0) head else head.copy(strength = strength)),
                featureVisibility = PlanarFeatureVisibility(false, false, false)))
        }
    }

    @Test fun editorPreviewShowsVisibleLocalTravelAtItsReal260DpPortraitSize() {
        prepare()
        compose.setContent { ZhiYuTheme { PlanarWorkshopDialog(controller, "可见动作测试") {} } }
        compose.onNodeWithTag("planar_preview").performClick()
        compose.waitUntil(10000) { controller.previewDocument != null && !controller.busy }
        assertNull(controller.error)
        assertNull("Preview must not publish the draft", controller.forBinding(binding))
        assertVisibleMotion("editor preview")
    }

    @Test fun savingAndReopeningAHandMarkedPackageShowsVisibleMotionInTheActualCall() {
        prepare()
        val phase = mutableStateOf("editor")
        val character = OcCallCharacterRules.resolve(null, OcAssetRef("b".repeat(32), "a".repeat(64), 1))!!
        compose.setContent { ZhiYuTheme {
            if (phase.value == "editor") PlanarWorkshopDialog(controller, "可见动作测试") {}
            else if (phase.value == "call") CallCharacterPreview(character, controller.forBinding(binding), 0f,
                { throw AssertionError("Saved hand-marked package must win") }, {}, Modifier.fillMaxSize())
        } }
        compose.onNodeWithTag("planar_save").performClick()
        compose.waitUntil(15000) { !controller.busy }
        assertNull("The real save callback must finish successfully", controller.error)
        assertNotNull("The real save must publish a package", controller.active)
        // Load the same on-disk package through the real controller before the call displays it.
        compose.runOnIdle { phase.value = "loading" }
        compose.waitForIdle()
        compose.runOnIdle { controller.connect(null) }
        compose.runOnIdle { controller.connect(binding) }
        compose.waitUntil(15000) { !controller.busy }
        assertNull("The saved package must load successfully", controller.error)
        assertNotNull("The call must use the package loaded from disk", controller.forBinding(binding))
        compose.runOnIdle { phase.value = "call" }
        compose.onNodeWithTag("call_character_animated").assertExists()
        assertVisibleMotion("saved call")
    }

    @Test fun theDefaultSmallHeadShowsVisibleLocalMotionInThe260DpWorkshop() {
        prepare(compactHead = true)
        previewCurrentDraft()
        assertVisibleMotion("default 180x235 head")
    }

    @Test fun aSmallHeadConvertedFromTheDefaultHalfStrengthAccessoryRemainsVisible() {
        prepare(strength = .5, compactHead = true)
        previewCurrentDraft()
        assertVisibleMotion("half-strength 180x235 head")
    }

    @Test fun zeroHeadStrengthKeepsTheSourceAndBackgroundExactlyStill() {
        prepare(strength = 0.0, compactHead = true)
        previewCurrentDraft()
        assertVisibleMotion("zero-strength 180x235 head", expectedMoving = false)
    }

    @Test fun aSavedLegacyDefaultHeadMovesWithoutResavingOrChangingItsPackageBytes() {
        prepare(compactHead = true)
        val draft = checkNotNull(controller.draft)
        val contractJson = context.assets.open("planar/contract.json").bufferedReader().use { it.readText() }
        val reader = PlanarPackageReader(contractJson)
        val source = portrait()
        val built = try { PlanarPhotoBuilder().build(source, draft.copy(suggestionsConfirmed = true),
            "旧默认头部", reader.contract) } finally { source.recycle() }
        val legacy = built.copy(layers = built.layers.map { layer ->
            if (layer.role != "face") layer else layer.copy(mesh = PlanarLocalHeadMotion.create(
                PlanarBasisFactory.create(intArrayOf(657, 171, 180, 235), "face", draft.anchors,
                    draft.parts.single().pivot, 1.0, 1220, 1224, reader.contract)))
        })
        val hash = MessageDigest.getInstance("SHA-256").digest(binding.toByteArray()).joinToString("") { "%02x".format(it) }
        val store = PlanarPackageStore(File(context.filesDir.canonicalFile, "planar-v1/$hash"), contractJson)
        store.save(legacy)
        val before = checkNotNull(store.activeIdentity())
        compose.runOnIdle { controller.connect(null); controller.connect(binding) }
        compose.waitUntil(15000) { !controller.busy }
        assertNull(controller.error)
        assertNotNull(controller.forBinding(binding))
        val character = OcCallCharacterRules.resolve(null, OcAssetRef("b".repeat(32), "a".repeat(64), 1))!!
        compose.setContent { ZhiYuTheme { CallCharacterPreview(character, controller.forBinding(binding), 0f,
            { throw AssertionError("A legacy saved package must win") }, {}, Modifier.fillMaxSize()) } }
        assertVisibleMotion("saved legacy default head")
        assertEquals("Rendering must never rewrite the user's saved package", before, store.activeIdentity())
    }

    private fun previewCurrentDraft() {
        compose.setContent { ZhiYuTheme { PlanarWorkshopDialog(controller, "可见动作测试") {} } }
        compose.onNodeWithTag("planar_preview").performClick()
        compose.waitUntil(10000) { controller.previewDocument != null && !controller.busy }
        assertNull(controller.error)
    }

    private fun assertVisibleMotion(entry: String, expectedMoving: Boolean = true) {
        var surface: PlanarSurfaceView? = null
        compose.waitUntil(10000) {
            compose.runOnUiThread {
                surface = WindowInspector.getGlobalWindowViews().asSequence().mapNotNull(::findSurface)
                    .firstOrNull { it.isShown && it.width > 0 && it.height > 0 && it.isAnimationRunning }
            }
            surface != null
        }
        val view = checkNotNull(surface)
        val density = view.resources.displayMetrics.density
        val centers = ArrayList<Double>()
        val backgroundX = ArrayList<Double>()
        val backgroundY = ArrayList<Double>()
        val deadline = SystemClock.uptimeMillis() + 4000
        while (SystemClock.uptimeMillis() < deadline) {
            val image = copySurface(view)
            try {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                val nose = markerCenter(pixels, image.width, image.height, intArrayOf(700, 200, 800, 350), true)
                val fixed = markerCenter(pixels, image.width, image.height, intArrayOf(70, 770, 230, 930), false)
                centers.add(nose.first / density)
                backgroundX.add(fixed.first / density)
                backgroundY.add(fixed.second / density)
            } finally { image.recycle() }
            Thread.sleep(180)
        }
        val travel = centers.max() - centers.min()
        assertTrue("A bounded observation needs enough real pixel samples", centers.size >= 8)
        assertEquals("The original background must stay horizontally fixed", 0.0, backgroundX.max() - backgroundX.min(), .05)
        assertEquals("The original background must stay vertically fixed", 0.0, backgroundY.max() - backgroundY.min(), .05)
        if (expectedMoving) assertTrue("$entry needs visible local motion at phone scale; horizontal travel was ${travel}dp", travel >= 1.2)
        else assertEquals("$entry must honor the user's zero strength", 0.0, travel, .05)
    }

    private fun markerCenter(pixels: IntArray, width: Int, height: Int, rectangle: IntArray,
        magenta: Boolean): Pair<Double, Double> {
        val fit = minOf(width / 1220.0, height / 1224.0)
        val left = (width - 1220 * fit) / 2; val top = (height - 1224 * fit) / 2
        var sumX = 0.0; var sumY = 0.0; var total = 0.0
        val x0 = (left + rectangle[0] * fit).toInt().coerceIn(0, width - 1)
        val x1 = (left + rectangle[2] * fit).toInt().coerceIn(x0 + 1, width)
        val y0 = (top + rectangle[1] * fit).toInt().coerceIn(0, height - 1)
        val y1 = (top + rectangle[3] * fit).toInt().coerceIn(y0 + 1, height)
        for (y in y0 until y1) for (x in x0 until x1) {
            val color = pixels[y * width + x]
            val weight = if (magenta) maxOf(0, minOf(Color.red(color), Color.blue(color)) - Color.green(color) - 80)
                else maxOf(0, Color.green(color) - maxOf(Color.red(color), Color.blue(color)) - 80)
            sumX += x * weight.toDouble(); sumY += y * weight.toDouble(); total += weight
        }
        assertTrue("The ${if (magenta) "nose" else "background"} marker must be visible", total > 0)
        return sumX / total to sumY / total
    }

    private fun copySurface(view: PlanarSurfaceView): Bitmap {
        val deadline = SystemClock.uptimeMillis() + 10000
        var lastStatus = PixelCopy.ERROR_SOURCE_NO_DATA
        do {
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val result = CompletableFuture<Int>()
            PixelCopy.request(view, image, { result.complete(it) }, Handler(Looper.getMainLooper()))
            lastStatus = result.get(5, TimeUnit.SECONDS)
            if (lastStatus == PixelCopy.SUCCESS) return image
            image.recycle()
            assertEquals("Only a missing initial Surface buffer is transient", PixelCopy.ERROR_SOURCE_NO_DATA, lastStatus)
            Thread.sleep(33)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("A real Surface frame was never produced (PixelCopy status $lastStatus)")
    }

    private fun findSurface(view: View): PlanarSurfaceView? {
        if (view is PlanarSurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun portrait(): Bitmap = Bitmap.createBitmap(IntArray(1220 * 1224) { index ->
        val x = index % 1220; val y = index / 1220
        when {
            x in 744..756 && y in 240..300 -> Color.MAGENTA
            x in 100..200 && y in 800..900 -> Color.GREEN
            x in 500..999 && y in 80..519 -> 0xffffddbb.toInt()
            else -> 0xff3399cc.toInt()
        }
    }, 1220, 1224, Bitmap.Config.ARGB_8888)
}
