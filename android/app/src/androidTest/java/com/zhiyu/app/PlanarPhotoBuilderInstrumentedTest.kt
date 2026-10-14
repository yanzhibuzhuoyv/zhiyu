package com.zhiyu.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiyu.app.data.planar.PlanarDocument
import com.zhiyu.app.data.planar.PlanarPhotoBuilder
import com.zhiyu.app.domain.planar.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PlanarPhotoBuilderInstrumentedTest {
    @Test fun neutralCompositionRetainsSourceAndPatchesUnderlyingOriginalEyes() {
        val source = fixture(128, 192) { x, y -> if (x in 46..55 && y in 44..48) Color.BLACK else Color.CYAN }
        try {
            val draft = PlanarDraft.suggest(128, 192).copy(suggestionsConfirmed = true)
            val document = PlanarPhotoBuilder().build(source, draft, "本机角色", contract())
            assertTrue(source.sameAs(compose(document)))
            val eye = document.layers.first { it.id == "eye_left" }
            val base = document.layers.first { it.id == "base" }
            assertEquals(Color.BLACK, layerPixel(eye.texturePng, eye.mesh.baseVertices(), 48, 46))
            assertEquals(Color.CYAN, layerPixel(base.texturePng, base.mesh.baseVertices(), 48, 46))
            assertTrue(document.definitionJson.contains("source-neighborhood"))
        } finally { source.recycle() }
    }

    @Test fun disconnectedWingAndWeaponPixelsRemainAndMoveInTheirOwnCroppedLayer() {
        val source = fixture(128, 192) { x, y ->
            when { x in 60..70 && y in 40..150 -> Color.GREEN
                x in 4..8 && y in 10..14 || x in 115..120 && y in 170..178 -> Color.MAGENTA
                else -> Color.TRANSPARENT }
        }
        val wing = PlanarPart("wing", "属翼", "accessory", 30.0,
            listOf(PlanarPoint(2.0, 8.0), PlanarPoint(11.0, 8.0), PlanarPoint(11.0, 18.0), PlanarPoint(2.0, 18.0)),
            PlanarPoint(64.0, 80.0), brush = listOf(PlanarBrushStroke(listOf(PlanarPoint(118.0, 174.0)), 6.0, true)))
        try {
            val document = PlanarPhotoBuilder().build(source,
                PlanarDraft.suggest(128, 192).copy(parts = listOf(wing), suggestionsConfirmed = true), "附件", contract())
            assertTrue(source.sameAs(compose(document)))
            val layer = document.layers.first { it.id == "wing" }
            assertEquals(Color.MAGENTA, layerPixel(layer.texturePng, layer.mesh.baseVertices(), 6, 12))
            assertEquals(Color.MAGENTA, layerPixel(layer.texturePng, layer.mesh.baseVertices(), 118, 174))
            assertTrue(layer.mesh.deltas().getValue("sway").any { it != 0f })
        } finally { source.recycle() }
    }

    @Test fun orderedSubjectEraseAndKeepModifyOnlyExplicitSourcePixels() {
        val source = fixture(64, 64) { _, _ -> Color.RED }
        try {
            val erase = PlanarBrushStroke(listOf(PlanarPoint(4.0, 4.0), PlanarPoint(12.0, 4.0)), 2.0, false)
            val keep = PlanarBrushStroke(listOf(PlanarPoint(4.0, 4.0)), 1.0, true)
            val draft = PlanarDraft.suggest(64, 64).copy(subjectBrush = listOf(erase, keep), suggestionsConfirmed = true)
            val document = PlanarPhotoBuilder().build(source, draft, "修正", contract())
            val result = compose(document)
            try {
                assertEquals(Color.RED, result.getPixel(4, 4))
                assertEquals(0, Color.alpha(result.getPixel(10, 4)))
                assertEquals(Color.RED, result.getPixel(30, 60))
            } finally { result.recycle() }
        } finally { source.recycle() }
    }

    @Test fun importedBinaryMaskAndSparseEditsRetainFractionalZ() {
        val source = fixture(64, 64) { _, _ -> Color.RED }
        val mask = fixture(64, 64) { x, y -> if (x in 2..7 && y in 2..7) Color.WHITE else Color.BLACK }
        try {
            val maskOutput = ByteArrayOutputStream()
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, maskOutput))
            val part = PlanarPart("imported", "导入附件", "accessory", 1.5, emptyList(), PlanarPoint(4.0, 4.0),
                brush = listOf(PlanarBrushStroke(listOf(PlanarPoint(50.0, 50.0)), 2.0, true)),
                baseMaskPng = Base64.encodeToString(maskOutput.toByteArray(), Base64.NO_WRAP))
            val subject = ByteArray(64 * 64) { 1 }.also { it[63 * 64 + 63] = 0 }
            val draft = PlanarDraft.suggest(64, 64).copy(parts = listOf(part), suggestionsConfirmed = true,
                subjectMask = Base64.encodeToString(subject, Base64.NO_WRAP))
            val document = PlanarPhotoBuilder().build(source, draft, "导入编辑", contract())
            val layer = document.layers.first { it.id == "imported" }
            assertEquals(1.5, layer.z, 0.0)
            assertEquals(Color.RED, layerPixel(layer.texturePng, layer.mesh.baseVertices(), 4, 4))
            assertEquals(Color.RED, layerPixel(layer.texturePng, layer.mesh.baseVertices(), 50, 50))
            assertTrue(document.definitionJson.contains("subjectMask"))
            val composed = compose(document)
            try { assertEquals(0, Color.alpha(composed.getPixel(63, 63))) } finally { composed.recycle() }
        } finally { source.recycle(); mask.recycle() }
    }

    @Test fun sixtyPartsAllocateOnlyCroppedTexturesAndKeepEveryIsland() {
        val source = fixture(256, 256) { _, _ -> Color.CYAN }
        try {
            val parts = (0 until 60).map { i ->
                val x = (i % 10) * 24.0
                // The mouth rectangle is [110,115,146,131]; the original row at y=120 hid part_5 completely.
                val y = 140.0 + (i / 10) * 20.0
                PlanarPart("part_$i", "附件$i", "accessory", i.toDouble(), listOf(
                    PlanarPoint(x, y), PlanarPoint(x + 8, y), PlanarPoint(x + 8, y + 8), PlanarPoint(x, y + 8)),
                    PlanarPoint(x + 4, y + 4))
            }
            val document = PlanarPhotoBuilder().build(source,
                PlanarDraft.suggest(256, 256).copy(parts = parts, suggestionsConfirmed = true), "多附件", contract())
            assertEquals(64, document.layers.size)
            assertEquals(parts.map { it.id }.toSet(), document.layers.filter { it.id.startsWith("part_") }.map { it.id }.toSet())
            document.layers.filter { it.id.startsWith("part_") }.forEach { layer ->
                val image = BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size)
                try { assertTrue(image.width <= 9 && image.height <= 9) } finally { image.recycle() }
                val pivot = parts.first { it.id == layer.id }.pivot
                assertEquals(Color.CYAN, layerPixel(layer.texturePng, layer.mesh.baseVertices(), pivot.x.toInt(), pivot.y.toInt()))
            }
            val composed = compose(document)
            try { assertTrue(source.sameAs(composed)) } finally { composed.recycle() }
        } finally { source.recycle() }
    }

    @Test fun semitransparentSourcePixelsDoNotGainAlphaThroughOverlaps() {
        val source = fixture(64, 64) { _, _ -> Color.argb(96, 50, 100, 200) }
        try {
            val document = PlanarPhotoBuilder().build(source,
                PlanarDraft.suggest(64, 64).copy(suggestionsConfirmed = true), "透明", contract())
            val composed = compose(document)
            try { assertTrue(source.sameAs(composed)) } finally { composed.recycle() }
        } finally { source.recycle() }
    }

    @Test fun confirmationEmptySubjectAndTextureBudgetReject() {
        val source = fixture(32, 32) { _, _ -> Color.RED }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                PlanarPhotoBuilder().build(source, PlanarDraft.suggest(32, 32), "待确认", contract())
            }
            val erase = PlanarBrushStroke(listOf(PlanarPoint(16.0, 16.0)), 32.0, false)
            assertThrows(IllegalArgumentException::class.java) {
                PlanarPhotoBuilder().build(source,
                    PlanarDraft.suggest(32, 32).copy(subjectBrush = listOf(erase), suggestionsConfirmed = true), "空", contract())
            }
        } finally { source.recycle() }
        val big = fixture(1536, 1536) { _, _ -> Color.RED }
        try {
            val parts = (0..2).map { i -> PlanarPart("islands_$i", "分离岛", "accessory", i.toDouble(),
                listOf(PlanarPoint(1.0 + i * 3, 1.0), PlanarPoint(2.0 + i * 3, 1.0), PlanarPoint(1.0 + i * 3, 2.0)),
                PlanarPoint(768.0, 768.0), brush = listOf(PlanarBrushStroke(listOf(PlanarPoint(1530.0 + i, 1530.0)), 1.0, true))) }
            assertThrows(IllegalArgumentException::class.java) {
                PlanarPhotoBuilder().build(big, PlanarDraft.suggest(1536, 1536).copy(parts = parts,
                    suggestionsConfirmed = true), "预算", contract())
            }
        } finally { big.recycle() }
    }

    private fun compose(document: PlanarDocument): Bitmap {
        val result = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        document.layers.sortedWith(compareBy({ it.z }, { it.id })).forEach { layer ->
            val image = BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size)
            val vertices = layer.mesh.baseVertices()
            canvas.drawBitmap(image, vertices[0], vertices[1], null)
            image.recycle()
        }
        return result
    }

    private fun layerPixel(png: ByteArray, vertices: FloatArray, x: Int, y: Int): Int {
        val image = BitmapFactory.decodeByteArray(png, 0, png.size)
        return try { image.getPixel(x - vertices[0].toInt(), y - vertices[1].toInt()) } finally { image.recycle() }
    }

    private fun fixture(width: Int, height: Int, pixel: (Int, Int) -> Int): Bitmap =
        Bitmap.createBitmap(IntArray(width * height) { pixel(it % width, it / width) }, width, height, Bitmap.Config.ARGB_8888)

    private fun contract() = PlanarContract(1536, 1089, 2048, linkedMapOf(
        "headX" to ParameterRange(-1f, 1f, 0f), "headY" to ParameterRange(-1f, 1f, 0f),
        "tilt" to ParameterRange(-1f, 1f, 0f), "blinkLeft" to ParameterRange(0f, 1f, 0f),
        "blinkRight" to ParameterRange(0f, 1f, 0f), "mouth" to ParameterRange(0f, 1f, 0f),
        "breath" to ParameterRange(0f, 1f, 0f), "sway" to ParameterRange(-1f, 1f, 0f)))
}
