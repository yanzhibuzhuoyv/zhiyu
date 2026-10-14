package com.zhiyu.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.domain.planar.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import rig2d.planar.PlanarMixer
import rig2d.planar.PlanarParameters

/** Local test-APK gallery. This checks pixels and geometry, not landmark detection or animation quality. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlanarBackgroundGalleryNativeTest {
    @Test fun scene01BackPose() = verify("scene_01")
    @Test fun scene02SeatedScene() = verify("scene_02")
    @Test fun scene03UpwardProfile() = verify("scene_03")
    @Test fun scene04FrontSnow() = verify("scene_04")
    @Test fun scene05ProfileStars() = verify("scene_05")
    @Test fun scene06FrontShelf() = verify("scene_06")
    @Test fun scene07FrontThrone() = verify("scene_07")
    @Test fun scene08ProfileDrink() = verify("scene_08")
    @Test fun scene09FrontArms() = verify("scene_09")
    @Test fun scene10FrontCloseup() = verify("scene_10")
    @Test fun scene11ProfileOtherCharacter() = verify("scene_11")
    @Test fun scene12FrontCord() = verify("scene_12")
    @Test fun scene13FrontOrnaments() = verify("scene_13")
    @Test fun scene14FrontBranches() = verify("scene_14")

    private fun verify(id: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val fixtures = JSONObject(assets.open("planar-background-gallery-fixtures.json").bufferedReader().use { it.readText() }).getJSONArray("cases")
        val fixture = (0 until fixtures.length()).map { fixtures.getJSONObject(it) }.first { it.getString("id") == id }
        val result = JSONObject().put("id", id).put("sourceName", fixture.getString("sourceName"))
            .put("asset", fixture.getString("assetPath")).put("expectedInputSha256", fixture.getString("sha256"))
            .put("manualNote", fixture.getString("manualNote")).put("globalMotionPartsUsed", false)
            .put("semanticAccuracyMeasured", false).put("animationQualityReviewed", false)
        var source: Bitmap? = null
        try {
            val raw = assets.open(fixture.getString("assetPath")).use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
            result.put("actualInputSha256", sha); assertEquals(fixture.getString("sha256"), sha)
            source = fit(checkNotNull(BitmapFactory.decodeByteArray(raw, 0, raw.size)), fixture)
            val original = pixels(source)
            val reader = instrumentation.targetContext.assets.open("planar/contract.json").bufferedReader().use { PlanarPackageReader(it.readText()) }
            val draft = draft(fixture, source.width, source.height)
            val started = SystemClock.elapsedRealtimeNanos()
            val document = PlanarPhotoBuilder().build(source, draft, id, reader.contract)
            result.put("builderMillis", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                .put("workingWidth", source.width).put("workingHeight", source.height).put("layerCount", document.layers.size)
                .put("sourcePngBytes", document.sourcePng.size).put("texturePngBytes", document.layers.sumOf { it.texturePng.size.toLong() })
            result.put("sourceArgbChangedPixels", differences(original, pixels(source)))
            assertEquals(0, result.getInt("sourceArgbChangedPixels"))
            verifyNeutral(document, original, result)
            verifyGeometry(document, draft.featureVisibility, result)
            result.put("passed", true)
        } catch (error: Throwable) { result.put("passed", false).put("error", error.message ?: error.javaClass.simpleName); throw error }
        finally { source?.recycle(); appendResult(result) }
    }

    private fun fit(decoded: Bitmap, fixture: JSONObject): Bitmap {
        var retainOriginal = false
        try {
            assertEquals(fixture.getInt("width"), decoded.width); assertEquals(fixture.getInt("height"), decoded.height)
            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= 1536) { retainOriginal = true; return decoded }
            val scale = 1536.0 / longest
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt().coerceAtLeast(2),
                (decoded.height * scale).roundToInt().coerceAtLeast(2), true)
        } finally { if (!retainOriginal) decoded.recycle() }
    }

    private fun draft(fixture: JSONObject, width: Int, height: Int): PlanarDraft {
        val sx = width.toDouble() / fixture.getInt("width"); val sy = height.toDouble() / fixture.getInt("height")
        val anchors = fixture.getJSONObject("anchors")
        fun point(key: String): PlanarPoint = anchors.getJSONArray(key).let { PlanarPoint(it.getDouble(0) * sx, it.getDouble(1) * sy) }
        fun rect(key: String): PlanarRect = anchors.getJSONArray(key).let {
            PlanarRect(it.getDouble(0) * sx, it.getDouble(1) * sy, it.getDouble(2) * sx, it.getDouble(3) * sy)
        }
        val visible = fixture.getJSONObject("featureVisibility")
        return PlanarDraft.suggest(width, height).copy(anchors = PlanarAnchors(point("head"), point("neck"),
            rect("eyeLeft"), rect("eyeRight"), rect("mouth")), suggestionsConfirmed = true,
            featureVisibility = PlanarFeatureVisibility(visible.getBoolean("eyeLeft"), visible.getBoolean("eyeRight"), visible.getBoolean("mouth")))
    }

    private fun verifyNeutral(document: PlanarDocument, original: IntArray, result: JSONObject) {
        val neutral = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(neutral)
            document.layers.sortedWith(compareBy({ it.z }, { it.id })).forEach { layer ->
                val texture = checkNotNull(BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size))
                try { val vertices = layer.mesh.baseVertices(); canvas.drawBitmap(texture, vertices[0], vertices[1], null) }
                finally { texture.recycle() }
            }
            result.put("neutralArgbDifferentPixels", differences(original, pixels(neutral)))
            assertEquals(0, result.getInt("neutralArgbDifferentPixels"))
        } finally { neutral.recycle() }
    }

    private fun verifyGeometry(document: PlanarDocument, visibility: PlanarFeatureVisibility, result: JSONObject) {
        val expected = listOf("eye_left" to visibility.eyeLeft, "eye_right" to visibility.eyeRight, "mouth" to visibility.mouth)
        expected.forEach { (id, enabled) -> assertEquals(enabled, document.layers.any { it.id == id }) }
        val background = document.layers.first { it.id == "base" }
        var checks = 0; var movementChecks = 0
        document.layers.forEach { layer -> document.contract.parameters.forEach { (channel, range) ->
            listOf(range.minimum, range.maximum).distinct().forEach { value ->
                val vertices = PlanarMixer.vertices(layer.mesh, PlanarParameters(document.contract, mapOf(channel to value)))
                assertTrue(vertices.all { it.isFinite() })
                if (layer === background) assertArrayEquals(background.mesh.baseVertices(), vertices, 0f)
                else if (!vertices.contentEquals(layer.mesh.baseVertices())) movementChecks++
                checks++
            }
        } }
        result.put("safeIndividualEndpointChecks", checks).put("nonzeroMotionEndpointChecks", movementChecks)
            .put("backgroundAllChannelsFixed", true)
        if (expected.any { it.second }) assertTrue(movementChecks > 0)
        else assertEquals(0, movementChecks)
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun differences(expected: IntArray, actual: IntArray): Int {
        assertEquals(expected.size, actual.size)
        return expected.indices.count { expected[it] != actual[it] }
    }

    companion object {
        private fun reportFile() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "planar-background-gallery-native-r1.json")
        @BeforeClass @JvmStatic fun resetReport() {
            reportFile().writeText(JSONObject().put("surface", "Android instrumentation / AVD").put("hardwareBenchmark", false)
                .put("expectedSourceCases", 14).put("startedAtMillis", System.currentTimeMillis()).put("cases", JSONArray()).toString(2))
        }
        @Synchronized private fun appendResult(result: JSONObject) {
            val file = reportFile(); val report = JSONObject(file.readText()); val cases = report.getJSONArray("cases")
            cases.put(result); report.put("recordedSourceCases", cases.length())
            report.put("passedSourceCases", (0 until cases.length()).count { cases.getJSONObject(it).optBoolean("passed", false) })
            file.writeText(report.toString(2), Charsets.UTF_8)
        }
    }
}
