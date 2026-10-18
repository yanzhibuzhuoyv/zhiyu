package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import org.junit.Assert.*
import org.junit.Test
import rig2d.planar.ParameterRange
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarMesh
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Isolated synthetic PNGs and EGL contexts; no app media, keys, microphone or network. */
class PlanarGlRendererNativeTest {
    @Test fun indexedTriangleKeepsPngTopAtTopAndLeavesOutsideTransparent() = withEgl(32, 32) {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1)
        val texture = png(2, 2, colors)
        val mesh = mesh(floatArrayOf(0f, 0f, 32f, 0f, 0f, 32f), intArrayOf(0, 1, 2))
        val renderer = PlanarGlRenderer(document(listOf(layer("triangle", 0, texture, mesh))))
        try {
            renderer.initialize(); renderer.resize(32, 32); renderer.update(0f, false); renderer.drawAt(0.0)
            val pixels = pixels(32, 32)
            assertColor(255, 0, 0, 255, pixels.color(4, 4))
            assertColor(0, 255, 0, 255, pixels.color(25, 3))
            assertColor(0, 0, 255, 255, pixels.color(3, 25))
            assertColor(0, 0, 0, 0, pixels.color(25, 25))
        } finally { renderer.release() }
    }

    @Test fun premultipliedPngBlendsOnceAndLayersSortByZ() = withEgl(32, 32) {
        val red = solid(0xffff0000.toInt())
        val blue = solid(0x800000ff.toInt())
        val geometry = quad()
        val renderer = PlanarGlRenderer(document(listOf(layer("blue", 10, blue, geometry), layer("red", 0, red, geometry))))
        try {
            renderer.initialize(); renderer.resize(32, 32); renderer.update(0f, false); renderer.drawAt(0.0)
            assertColor(127, 0, 128, 255, pixels(32, 32).color(16, 16))
        } finally { renderer.release() }
        val transparent = PlanarGlRenderer(document(listOf(layer("alpha", 0, solid(0x80ff0000.toInt()), geometry))))
        try {
            transparent.initialize(); transparent.resize(32, 32); transparent.update(0f, false); transparent.drawAt(0.0)
            assertColor(128, 0, 0, 128, pixels(32, 32).color(16, 16))
        } finally { transparent.release() }
    }

    @Test fun fitAddsHorizontalLetterboxWithoutStretching() = withEgl(64, 32) {
        val renderer = PlanarGlRenderer(document(listOf(layer("square", 0, solid(-1), quad()))))
        try {
            renderer.initialize(); renderer.resize(64, 32); renderer.update(0f, false); renderer.drawAt(0.0)
            val output = pixels(64, 32)
            assertColor(0, 0, 0, 0, output.color(15, 16))
            assertColor(255, 255, 255, 255, output.color(16, 16))
            assertColor(255, 255, 255, 255, output.color(47, 16))
            assertColor(0, 0, 0, 0, output.color(48, 16))
        } finally { renderer.release() }
    }

    @Test fun suppliedMouthUsesBlendshapeAndIdleChannelsRespectTheContract() = withEgl(32, 32) {
        val base = floatArrayOf(8f, 8f, 24f, 8f, 24f, 24f, 8f, 24f)
        val moved = mesh(base, intArrayOf(0, 1, 2, 0, 2, 3), mapOf("mouth" to FloatArray(8) { if (it % 2 == 0) 4f else 0f }))
        val frames = mutableListOf<PlanarFrame>()
        val renderer = PlanarGlRenderer(document(listOf(layer("mouth", 0, solid(-1), moved))), onFrame = frames::add)
        try {
            renderer.initialize(); renderer.resize(32, 32); renderer.update(0f, false); renderer.drawAt(4.3)
            assertEquals(255, pixels(32, 32).color(9, 16)[3])
            assertEquals(0f, frames.last().parameters.getValue("blinkLeft"), 0f)
            renderer.update(1f, false); renderer.drawAt(4.3)
            assertEquals(0, pixels(32, 32).color(9, 16)[3])
            assertEquals(255, pixels(32, 32).color(27, 16)[3])
            assertEquals(1f, frames.last().parameters.getValue("mouth"), 0f)
            renderer.update(0f, true); renderer.drawAt(4.3)
            assertTrue(frames.last().parameters.getValue("blinkLeft") > .99f)
            assertTrue(frames.last().parameters.getValue("breath") > 0f)
            for ((name, value) in frames.last().parameters) {
                val range = contract.parameters.getValue(name)
                assertTrue("$name must stay in contract", value in range.minimum..range.maximum)
            }
        } finally { renderer.release() }
    }

    @Test fun newContextReuploadsResourcesAndDoesNotDeleteNewGenerationTexture() {
        val egl = EglFixture(32, 32)
        val frames = mutableListOf<PlanarFrame>()
        val renderer = PlanarGlRenderer(document(listOf(layer("square", 0, solid(-1), quad()))), onFrame = frames::add)
        try {
            renderer.initialize(); renderer.resize(32, 32); renderer.update(0f, false); renderer.drawAt(0.0)
            val generation = frames.last().generation
            egl.recreate()
            val sentinel = IntArray(1)
            GLES20.glGenTextures(1, sentinel, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sentinel[0])
            assertTrue(GLES20.glIsTexture(sentinel[0]))
            renderer.initialize(); renderer.resize(32, 32); renderer.drawAt(0.0)
            assertTrue(frames.last().generation > generation)
            assertTrue("Old IDs must never delete a new context resource", GLES20.glIsTexture(sentinel[0]))
            assertColor(255, 255, 255, 255, pixels(32, 32).color(16, 16))
            GLES20.glDeleteTextures(1, sentinel, 0)
        } finally { renderer.release(); egl.close() }
    }

    @Test fun latestPendingDocumentWinsAndFailedReloadCanRecoverBeforeStopDiscardsPendingWork() = withEgl(32,32) {
        val red=document(listOf(layer("solid",0,solid(0xffff0000.toInt()),quad())))
        val green=document(listOf(layer("solid",0,solid(0xff00ff00.toInt()),quad())))
        val blue=document(listOf(layer("solid",0,solid(0xff0000ff.toInt()),quad())))
        val frames=mutableListOf<PlanarFrame>()
        val failures=mutableListOf<RuntimeException>()
        val renderer=PlanarGlRenderer(red,failures::add,frames::add)
        try {
            renderer.initialize();renderer.resize(32,32);renderer.drawAt(0.0)
            val initialGeneration=frames.last().generation
            renderer.submitDocument(green);renderer.submitDocument(blue);renderer.onDrawFrame(null)
            assertColor(0,0,255,255,pixels(32,32).color(16,16))
            assertTrue(frames.last().generation>initialGeneration)
            renderer.submitDocument(red.copy(layers=emptyList()));renderer.onDrawFrame(null)
            assertEquals(1,failures.size)
            val failedCount=frames.size
            renderer.submitDocument(green);renderer.onDrawFrame(null)
            assertEquals(failedCount+1,frames.size)
            assertColor(0,255,0,255,pixels(32,32).color(16,16))
            val stoppedCount=frames.size
            renderer.stop();renderer.submitDocument(blue);renderer.onDrawFrame(null)
            assertEquals(stoppedCount,frames.size)
        } finally {renderer.release()}
    }

    private fun layer(id: String, z: Int, texture: ByteArray, mesh: PlanarMesh) = PlanarLayer(id, "body", z.toDouble(), texture, mesh)
    private fun document(layers: List<PlanarLayer>) = PlanarDocument(32, 32, "renderer", solid(-1), layers, "{}", contract)
    private fun quad() = mesh(floatArrayOf(0f, 0f, 32f, 0f, 32f, 32f, 0f, 32f), intArrayOf(0, 1, 2, 0, 2, 3))
    private fun mesh(vertices: FloatArray, indices: IntArray, deltas: Map<String, FloatArray> = emptyMap()): PlanarMesh {
        val uv = if (vertices.size == 6) floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f) else floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        return PlanarMesh(contract, vertices, uv, indices, deltas)
    }
    private fun solid(color: Int) = png(1, 1, intArrayOf(color))
    private fun png(width: Int, height: Int, colors: IntArray): ByteArray {
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private fun pixels(width: Int, height: Int): Pixels {
        GLES20.glFinish()
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        return Pixels(width, height, buffer)
    }
    private fun assertColor(r: Int, g: Int, b: Int, a: Int, actual: IntArray) {
        for ((channel, expected) in intArrayOf(r, g, b, a).withIndex()) assertEquals("RGBA channel $channel", expected.toDouble(), actual[channel].toDouble(), 2.0)
    }
    private inline fun withEgl(width: Int, height: Int, body: () -> Unit) {
        val fixture = EglFixture(width, height)
        try { body() } finally { fixture.close() }
    }
    private data class Pixels(val width: Int, val height: Int, val bytes: ByteBuffer) {
        fun color(x: Int, y: Int): IntArray = IntArray(4) { bytes.get(((height - 1 - y) * width + x) * 4 + it).toInt() and 255 }
    }
    private class EglFixture(private val width: Int, private val height: Int) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: android.opengl.EGLConfig
        private var context = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE
        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
            val attrs = intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] == 1)
            config = checkNotNull(configs[0]); create()
        }
        private fun create() {
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
        }
        fun recreate() { destroy(); create() }
        private fun destroy() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT
        }
        fun close() { destroy(); EGL14.eglReleaseThread(); EGL14.eglTerminate(display) }
    }
    private val contract = PlanarContract(32, 16, 16, listOf("headX", "headY", "tilt", "sway", "blinkLeft", "blinkRight", "mouth", "breath")
        .associateWith { ParameterRange(if (it in setOf("headX", "headY", "tilt", "sway")) -1f else 0f, 1f, 0f) })
}
