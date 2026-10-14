package com.zhiyu.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiyu.app.data.planar.*
import com.zhiyu.app.domain.planar.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMixer
import rig2d.planar.PlanarParameters

@RunWith(AndroidJUnit4::class)
class PlanarFixedBackgroundNativeTest {
    @Test fun originalSceneIsPreservedAndAllBackgroundVerticesRemainStatic() {
        val source = fixture { x, y -> Color.rgb(x * 2, y, (x + y) % 256) }
        try {
            val document = build(source, PlanarDraft.suggest(128, 192))
            val neutral = compose(document)
            try { assertTrue(source.sameAs(neutral)) } finally { neutral.recycle() }
            val background = document.layers.first { it.id == "base" }
            document.contract.parameters.forEach { (channel, range) ->
                val moved = PlanarMixer.vertices(background.mesh, PlanarParameters(document.contract, mapOf(channel to range.maximum)))
                assertArrayEquals(background.mesh.baseVertices(), moved, 0f)
            }
            val metadata = JSONObject(document.definitionJson).getJSONObject("metadata")
            assertTrue(metadata.getBoolean("backgroundRetained")); assertTrue(metadata.getBoolean("backgroundFixed"))
            assertFalse(metadata.getBoolean("hiddenPatchIsBackgroundReconstruction"))
        } finally { source.recycle() }
    }

    @Test fun aSubjectEraseStrokeOnlyRestrictsMotionAndNeverDeletesOriginalBackground() {
        val source = fixture { _, _ -> Color.CYAN }
        try {
            val eraseAll = PlanarBrushStroke(listOf(PlanarPoint(64.0, 96.0)), 192.0, false)
            val document = build(source, PlanarDraft.suggest(128, 192).copy(subjectBrush = listOf(eraseAll)))
            assertEquals(listOf("base"), document.layers.map { it.id })
            val neutral = compose(document)
            try { assertTrue(source.sameAs(neutral)) } finally { neutral.recycle() }
        } finally { source.recycle() }
    }

    @Test fun invisibleEyesAndMouthAreNotInventedForBackOrProfileViews() {
        val source = fixture { _, _ -> Color.CYAN }
        try {
            val visibility = PlanarFeatureVisibility(eyeLeft = false, eyeRight = false, mouth = false)
            val document = build(source, PlanarDraft.suggest(128, 192).copy(featureVisibility = visibility))
            assertEquals(listOf("base"), document.layers.map { it.id })
            val flags = JSONObject(document.definitionJson).getJSONObject("featureVisibility")
            assertFalse(flags.getBoolean("eyeLeft")); assertFalse(flags.getBoolean("eyeRight")); assertFalse(flags.getBoolean("mouth"))
            val neutral = compose(document)
            try { assertTrue(source.sameAs(neutral)) } finally { neutral.recycle() }
        } finally { source.recycle() }
    }

    @Test fun unattachedFeaturesDoNotSlideAcrossTheStationaryFace() {
        val source = fixture { _, _ -> Color.CYAN }
        try {
            val document = build(source, PlanarDraft.suggest(128, 192))
            document.layers.filter { it.id != "base" }.forEach { layer ->
                listOf("headX", "headY", "tilt", "breath", "sway").forEach { channel ->
                    assertArrayEquals(layer.mesh.baseVertices(), PlanarMixer.vertices(layer.mesh,
                        PlanarParameters(document.contract, mapOf(channel to 1f))), 0f)
                }
            }
        } finally { source.recycle() }
    }

    @Test fun aManuallyMarkedFaceCarriesItsOwnHiddenEyePatches() {
        val skin = Color.rgb(240, 190, 150)
        val source = fixture { x, y -> when {
            x in 48..55 && y in 44..48 -> Color.BLACK
            x in 35..93 && y in 33..76 -> skin
            else -> Color.GREEN
        } }
        try {
            val draft = PlanarDraft.suggest(128, 192)
            val face = PlanarPart("face_main", "人工人物头部", "face", 20.0, listOf(
                PlanarPoint(35.0, 33.0), PlanarPoint(94.0, 33.0), PlanarPoint(94.0, 77.0), PlanarPoint(35.0, 77.0)), draft.anchors.head)
            val document = build(source, draft.copy(parts = listOf(face)))
            val head = document.layers.first { it.id == "face_main" }
            val eye = document.layers.first { it.id == "eye_left" }
            val background = document.layers.first { it.id == "base" }
            assertEquals(skin, pixel(head, 50, 46))
            assertEquals(Color.GREEN, pixel(background, 50, 46))
            assertTrue(head.mesh.deltas().getValue("headX").any { it != 0f })
            assertTrue(eye.mesh.deltas().getValue("headX").any { it != 0f })
            val neutral = compose(document)
            try { assertTrue(source.sameAs(neutral)) } finally { neutral.recycle() }
        } finally { source.recycle() }
    }

    private fun build(source: Bitmap, draft: PlanarDraft) = PlanarPhotoBuilder().build(source,
        draft.copy(suggestionsConfirmed = true), "保留场景", contract())
    private fun fixture(pixel: (Int, Int) -> Int) = Bitmap.createBitmap(
        IntArray(128 * 192) { pixel(it % 128, it / 128) }, 128, 192, Bitmap.Config.ARGB_8888)
    private fun compose(document: PlanarDocument): Bitmap {
        val output = Bitmap.createBitmap(document.width, document.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        document.layers.sortedWith(compareBy({ it.z }, { it.id })).forEach { layer ->
            val texture = BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size)
            try { val vertices = layer.mesh.baseVertices(); canvas.drawBitmap(texture, vertices[0], vertices[1], null) }
            finally { texture.recycle() }
        }
        return output
    }
    private fun pixel(layer: PlanarLayer, x: Int, y: Int): Int {
        val texture = BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size)
        return try { val vertices = layer.mesh.baseVertices(); texture.getPixel(x - vertices[0].toInt(), y - vertices[1].toInt()) }
        finally { texture.recycle() }
    }
    private fun contract() = PlanarContract(1536, 1089, 2048, linkedMapOf(
        "headX" to ParameterRange(-1f, 1f, 0f), "headY" to ParameterRange(-1f, 1f, 0f),
        "tilt" to ParameterRange(-1f, 1f, 0f), "blinkLeft" to ParameterRange(0f, 1f, 0f),
        "blinkRight" to ParameterRange(0f, 1f, 0f), "mouth" to ParameterRange(0f, 1f, 0f),
        "breath" to ParameterRange(0f, 1f, 0f), "sway" to ParameterRange(-1f, 1f, 0f)))
}
