package com.zhiyu.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalPortraitProcessor
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.domain.PortraitPoint
import com.zhiyu.app.domain.PortraitStroke
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.UUID

internal class IsolatedVisualContext(base: Context) : ContextWrapper(base) {
    private val prefix = "portrait_test_${UUID.randomUUID()}_"
    private val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
    private val preferences = mutableSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val scoped = prefix + name
        preferences.add(scoped)
        return baseContext.getSharedPreferences(scoped, mode)
    }
    fun clean() {
        preferences.forEach { baseContext.deleteSharedPreferences(it) }
        check(directory.canonicalPath.startsWith(baseContext.cacheDir.canonicalPath + File.separator))
        directory.deleteRecursively()
    }
}

internal fun portraitFixture(width: Int = 128, height: Int = 96, color: (Int, Int) -> Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.setPixels(IntArray(width * height) { color(it % width, it / width) }, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return output.toByteArray()
    } finally { bitmap.recycle() }
}
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

@RunWith(AndroidJUnit4::class)
class LocalPortraitInstrumentedTest {
    private lateinit var context: IsolatedVisualContext
    private lateinit var processor: LocalPortraitProcessor
    @Before fun setup() {
        context = IsolatedVisualContext(InstrumentationRegistry.getInstrumentation().targetContext)
        processor = LocalPortraitProcessor(context)
    }
    @After fun cleanup() { context.clean() }
    @Test fun existingTransparentSubjectIncludesDetachedParts() {
        val raw = portraitFixture { x, y -> if (x in 50..73 && y in 30..53 || x in 10..15 && y in 12..16 || x in 116..121 && y in 70..76) Color.MAGENTA else Color.TRANSPARENT }
        val before = digest(raw)
        val result = processor.extract(raw)
        assertEquals("existing_alpha", result.method)
        assertEquals(listOf(10, 12, 122, 77), result.bounds)
        assertTrue(alphaAt(result.subject, 118 - 10, 73 - 12) > 0)
        assertEquals(before, digest(raw))
    }
    @Test fun sixPairsOfWingsAndSeparateWeaponSurviveUnion() {
        val original = portraitFixture { _, _ -> Color.CYAN }
        val subject = portraitFixture(24, 24) { _, _ -> Color.CYAN }
        val parts = (0..5).flatMap { row -> listOf(PortraitPoint(.10f, .15f + row * .11f), PortraitPoint(.90f, .15f + row * .11f)) } + PortraitPoint(.96f, .91f)
        val strokes = parts.map { PortraitStroke("keep", listOf(it), .015f) }
        val before = digest(original)
        val result = processor.refine(original, subject, listOf(50, 30, 74, 54), strokes)
        assertEquals("manual_refinement", result.method)
        assertEquals(13, parts.size)
        parts.forEachIndexed { index, point ->
            val x = Math.round(point.x * 127) - result.bounds[0]
            val y = Math.round(point.y * 95) - result.bounds[1]
            assertEquals("part $index missing at original=(${x + result.bounds[0]},${y + result.bounds[1]}), bounds=${result.bounds}",
                255, alphaAt(result.subject, x, y))
        }
        // radius=round(.015*96)=1; Android's non-AA integer-centred circle covers
        // x=12..13 for the left wing and x=121..122 for the weapon. bbox right/bottom exclude.
        assertEquals("all 12 wings + weapon + existing torso envelope; actual=${result.bounds}",
            listOf(12, 13, 123, 87), result.bounds)
        assertEquals(255, alphaAt(result.subject, 60 - result.bounds[0], 40 - result.bounds[1]))
        assertEquals(before, digest(original))
    }
    @Test fun sourceTransparencyAndRepeatedHalfOpacityArePreserved() {
        val original = portraitFixture(80, 60) { x, y -> if (x < 15 && y < 15) Color.TRANSPARENT else Color.argb(128, 100, 60, 20) }
        val subject = portraitFixture(10, 10) { _, _ -> Color.argb(128, 100, 60, 20) }
        val strokes = listOf(PortraitStroke("keep", listOf(PortraitPoint(.1f, .1f), PortraitPoint(.5f, .5f)), .02f))
        val first = processor.refine(original, subject, listOf(35, 25, 45, 35), strokes)
        val second = processor.refine(original, first.subject, first.bounds, strokes)
        assertEquals(128, alphaAt(first.subject, 40 - first.bounds[0], 30 - first.bounds[1]))
        assertEquals(128, alphaAt(second.subject, 40 - second.bounds[0], 30 - second.bounds[1]))
        for (y in second.bounds[1] until minOf(15, second.bounds[3]))
            for (x in second.bounds[0] until minOf(15, second.bounds[2]))
                assertEquals(0, alphaAt(second.subject, x - second.bounds[0], y - second.bounds[1]))
    }
    @Test fun continuousBrushAndEraseUseOriginalCoordinates() {
        val original = portraitFixture(80, 60) { _, _ -> Color.RED }
        val subject = portraitFixture(10, 10) { _, _ -> Color.RED }
        val strokes = listOf(PortraitStroke("keep", listOf(PortraitPoint(.1f, .8f), PortraitPoint(.9f, .8f)), .02f),
            PortraitStroke("remove", listOf(PortraitPoint(.5f, .5f)), .03f))
        val result = processor.refine(original, subject, listOf(35, 25, 45, 35), strokes)
        assertTrue(alphaAt(result.subject, 40 - result.bounds[0], 47 - result.bounds[1]) > 0)
        assertEquals(0, alphaAt(result.subject, 40 - result.bounds[0], 30 - result.bounds[1]))
    }
    @Test fun emptyMaskAndWrongBoundsAreRejectedWithoutOriginalMutation() {
        val original = portraitFixture(80, 60) { _, _ -> Color.BLUE }
        val subject = portraitFixture(1, 1) { _, _ -> Color.BLUE }
        val before = digest(original)
        rejects { processor.refine(original, subject, listOf(35, 25, 37, 26), listOf(PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), .02f))) }
        rejects { processor.refine(original, subject, listOf(40, 30, 41, 31), listOf(PortraitStroke("remove", listOf(PortraitPoint(.5f, .5f)), .1f))) }
        assertEquals(before, digest(original))
    }
    @Test fun blankManualCanvasCannotCountAsSuccessfulAutomaticSubject() {
        val original = portraitFixture { _, _ -> Color.WHITE }
        val blank = processor.manualStart(original)
        assertEquals("manual_selection", blank.method)
        assertFalse(processor.hasVisiblePixels(blank.subject))
        val result = processor.refine(original, blank.subject, blank.bounds, listOf(PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), .05f)))
        assertTrue(processor.hasVisiblePixels(result.subject))
    }
    @Test fun bundledOnnxExecutesRealCpuInferenceWithoutNetwork() {
        val model = context.assets.open("u2netp.onnx").use { it.readBytes() }
        assertEquals("309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8", digest(model))
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(2)
            environment.createSession(model, options).use { session ->
                val input = FloatArray(3 * 320 * 320) { (it % 127).toFloat() / 127 }
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 320, 320)).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                        val result = output[0] as OnnxTensor
                        assertEquals(320 * 320, result.floatBuffer.remaining())
                        val values = FloatArray(320 * 320); result.floatBuffer.get(values)
                        assertTrue(values.all { it.isFinite() })
                    }
                }
            }
        }
    }
    @Test fun largeLegalJpegUsesBoundedWorkingCanvasAndRetainsRawSha() {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val raw = try {
            bitmap.eraseColor(Color.rgb(80, 120, 160))
            val output = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
        val before = digest(raw)
        val result = processor.manualStart(raw)
        assertEquals(4000, result.sourceWidth)
        assertEquals(3000, result.sourceHeight)
        assertTrue(result.width <= 1536 && result.height <= 1536)
        assertTrue(result.width.toLong() * result.height <= 4_000_000)
        val refined = processor.refine(raw, result.subject, result.bounds,
            listOf(PortraitStroke("keep", listOf(PortraitPoint(.5f, .5f)), .1f)))
        assertTrue(refined.width <= 1536 && refined.height <= 1536)
        assertEquals(before, digest(raw))
        val store = LocalMediaStore(context)
        val retained = store.ingest("image", raw, purpose = "portrait")
        assertArrayEquals(raw, store.download(retained.id, retained.sha256))
    }
    private fun alphaAt(raw: ByteArray, x: Int, y: Int): Int {
        val bitmap = LocalPortraitProcessor.decode(raw)
        try { return Color.alpha(bitmap.getPixel(x, y)) } finally { bitmap.recycle() }
    }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("expected invalid mask") } catch (_: IllegalArgumentException) { }
    }
}
