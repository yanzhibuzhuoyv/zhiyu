package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.planar.DetectedFaceRegion
import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.PlanarAnimation
import rig2d.planar.PlanarMixer
import kotlin.math.abs

class AutomaticCallPlanarNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun contract() = PlanarPackageReader(context.assets.open("planar/contract.json").bufferedReader().use { it.readText() }).contract
    private fun source() = Bitmap.createBitmap(IntArray(320 * 480) { index ->
        val x = index % 320; val y = index / 320
        if ((x-160)*(x-160)/2500.0+(y-135)*(y-135)/3600.0 < 1) Color.rgb(220, 170, 130)
        else Color.rgb(x % 256, y % 256, (x+y) % 256)
    }, 320, 480, Bitmap.Config.ARGB_8888)

    @Test fun reliableDetectionProducesRealMovingLocalGeometryAndFixedOriginalScene() {
        val original = source()
        try {
            val document = requireNotNull(AutomaticCallPlanarBuilder(contract()) {
                listOf(DetectedFaceRegion(160.0, 125.0, 40.0, .93))
            }.build(original))
            val neutral = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(neutral)
                document.layers.forEach { layer ->
                    val texture = BitmapFactory.decodeByteArray(layer.texturePng, 0, layer.texturePng.size)
                    try { val vertices = layer.mesh.baseVertices(); canvas.drawBitmap(texture, vertices[0], vertices[1], null) }
                    finally { texture.recycle() }
                }
                assertTrue(original.sameAs(neutral))
            } finally { neutral.recycle() }
            assertEquals(setOf("base", "auto_face"), document.layers.map { it.id }.toSet())
            val base = document.layers.first { it.id == "base" }
            val face = document.layers.first { it.id == "auto_face" }
            val poses = listOf(0.0, 1.1, 2.7).map { PlanarAnimation.sample(document.contract, it, .8f, true) }
            poses.forEach { assertArrayEquals(base.mesh.baseVertices(), PlanarMixer.vertices(base.mesh, it), 0f) }
            val first = PlanarMixer.vertices(face.mesh, poses.first())
            assertTrue(poses.drop(1).any { pose -> !first.contentEquals(PlanarMixer.vertices(face.mesh, pose)) })
            val idle = PlanarMixer.vertices(face.mesh, PlanarAnimation.sample(document.contract, 1.3, .8f, true))
            val neutralFace = face.mesh.baseVertices()
            assertTrue(idle.indices.maxOf { abs(idle[it] - neutralFace[it]) } >= .5f)
            assertTrue(face.mesh.deltas().getValue("mouth").all { it == 0f })
            assertTrue(face.mesh.deltas().getValue("blinkLeft").all { it == 0f })
        } finally { original.recycle() }
    }

    @Test fun failedOrAmbiguousDetectionReturnsNoFakeAnimationAndLeavesOriginalUntouched() {
        val original = source(); val before = original.copy(Bitmap.Config.ARGB_8888, false)
        try {
            assertNull(AutomaticCallPlanarBuilder(contract()) { emptyList() }.build(original))
            assertNull(AutomaticCallPlanarBuilder(contract()) {
                listOf(DetectedFaceRegion(120.0, 125.0, 40.0, .93), DetectedFaceRegion(220.0, 125.0, 40.0, .93))
            }.build(original))
            assertTrue(before.sameAs(original)); assertFalse(original.isRecycled)
        } finally { original.recycle(); before.recycle() }
    }
}
