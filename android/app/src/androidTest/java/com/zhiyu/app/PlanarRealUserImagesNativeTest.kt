package com.zhiyu.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.planar.PlanarDocument
import com.zhiyu.app.data.planar.PlanarPackageReader
import com.zhiyu.app.data.planar.PlanarPhotoBuilder
import com.zhiyu.app.domain.planar.PlanarAnchors
import com.zhiyu.app.domain.planar.PlanarDraft
import com.zhiyu.app.domain.planar.PlanarPoint
import com.zhiyu.app.domain.planar.PlanarRect
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMixer
import rig2d.planar.PlanarParameters

/** These original images are installed in the test APK only; landmarks are manually supplied. */
@RunWith(AndroidJUnit4::class)
class PlanarRealUserImagesNativeTest {
    @Test fun reviewedRealImagesKeepNeutralPixelsAndSafeIndividualChannelEndpoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val results = JSONArray()
        val report = JSONObject().put("surface", "Android instrumentation / AVD")
            .put("hardwareBenchmark", false).put("automaticSegmentationAccuracyMeasured", false)
            .put("startedAtMillis", System.currentTimeMillis()).put("cases", results)
        val output = File(instrumentation.targetContext.filesDir, "planar-user-native-r1.json")
        val contract = instrumentation.targetContext.assets.open("planar/contract.json").bufferedReader().use {
            PlanarPackageReader(it.readText()).contract
        }
        try {
            fixtures.forEach { fixture ->
                val result = JSONObject().put("asset", fixture.asset).put("landmarks", "manual")
                results.put(result)
                try { verify(fixture, contract, result); result.put("passed", true) }
                catch (error: Throwable) { result.put("passed", false).put("error", error.message ?: error.javaClass.simpleName); throw error }
            }
        } finally { output.writeText(report.toString(2), Charsets.UTF_8) }
    }

    private fun verify(fixture: Fixture, contract: PlanarContract, result: JSONObject) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val source = assets.open("user_oc/${fixture.asset}").use { input ->
            checkNotNull(BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
            })) { "User test image could not be decoded" }
        }
        try {
            result.put("width", source.width).put("height", source.height)
            assertEquals(fixture.width, source.width); assertEquals(fixture.height, source.height)
            assertTrue(maxOf(source.width, source.height) <= 1536)
            val original = pixels(source)
            val draft = PlanarDraft.suggest(source.width, source.height)
                .copy(anchors = fixture.anchors, suggestionsConfirmed = true)
            val started = SystemClock.elapsedRealtimeNanos()
            val document = PlanarPhotoBuilder().build(source, draft, fixture.asset, contract)
            result.put("builderMillis", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                .put("sourcePngBytes", document.sourcePng.size).put("layerCount", document.layers.size)
                .put("layerPngBytes", document.layers.sumOf { it.texturePng.size.toLong() })
                .put("totalPngBytes", document.sourcePng.size.toLong() + document.layers.sumOf { it.texturePng.size.toLong() })
            val changedSource = differences(original, pixels(source))
            result.put("sourceArgbChangedPixels", changedSource)
            assertEquals("Generation changed the original bitmap", 0, changedSource)
            val neutral = compose(document)
            try {
                val changedNeutral = differences(original, pixels(neutral))
                result.put("neutralArgbDifferentPixels", changedNeutral)
                assertEquals("Neutral layers no longer match the original RGBA pixels", 0, changedNeutral)
            } finally { neutral.recycle() }
            assertTrue(document.layers.map { it.id }.containsAll(listOf("base", "eye_left", "eye_right", "mouth")))
            result.put("safeIndividualEndpointChecks", checkEndpoints(document))
        } finally { source.recycle() }
    }

    private fun checkEndpoints(document: PlanarDocument): Int {
        var checks = 0
        document.layers.forEach { layer ->
            document.contract.parameters.forEach { (channel, range) ->
                listOf(range.minimum, range.maximum).distinct().forEach { endpoint ->
                    val parameters = PlanarParameters(document.contract, mapOf(channel to endpoint))
                    // The core mixer checks every source triangle for collapse or a changed orientation.
                    val vertices = PlanarMixer.vertices(layer.mesh, parameters)
                    assertTrue("Nonfinite endpoint for ${layer.id}/$channel", vertices.all { it.isFinite() })
                    checks++
                }
            }
        }
        return checks
    }

    private fun compose(document: PlanarDocument): Bitmap {
        val result = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result)
            document.layers.sortedWith(compareBy({ it.z }, { it.id })).forEach { layer ->
                val texture = checkNotNull(BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size))
                try { val vertices = layer.mesh.baseVertices(); canvas.drawBitmap(texture, vertices[0], vertices[1], null) }
                finally { texture.recycle() }
            }
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun differences(expected: IntArray, actual: IntArray): Int {
        assertEquals(expected.size, actual.size)
        return expected.indices.count { expected[it] != actual[it] }
    }

    private data class Fixture(val asset: String, val width: Int, val height: Int, val anchors: PlanarAnchors)
    private val fixtures = listOf(
        Fixture("sword.png", 1045, 1506, PlanarAnchors(
            PlanarPoint(537.0, 284.0), PlanarPoint(528.0, 374.0),
            PlanarRect(507.0, 277.0, 550.0, 304.0), PlanarRect(550.0, 243.0, 577.0, 278.0),
            PlanarRect(551.0, 307.0, 579.0, 331.0))),
        Fixture("portrait.jpg", 1080, 1438, PlanarAnchors(
            PlanarPoint(540.0, 711.0), PlanarPoint(540.0, 953.0),
            PlanarRect(305.0, 536.0, 475.0, 654.0), PlanarRect(591.0, 527.0, 782.0, 640.0),
            PlanarRect(449.0, 789.0, 630.0, 833.0))),
    )
}
