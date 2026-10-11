package com.zhiyu.app.data

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.zhiyu.app.domain.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Actual depth-tested ES 2.0 geometry. UI preview and PNG inventory use the same mesh/shaders. */
class AvatarGlRenderer(identity: OcIdentity, scene: OcScene = OcScene(),
    private val onPreviewFailure: (RuntimeException) -> Unit = { throw it }) : GLSurfaceView.Renderer {
    @Volatile private var requested = identity to scene
    private var drawn: Pair<OcIdentity, OcMotion>? = null
    private var buffer: FloatBuffer? = null
    private var count = 0
    private var meshBounds = floatArrayOf(-1f, -1.5f, -1f, 1f, 1.5f, 1f)
    private var program = 0
    private var position = -1; private var normal = -1; private var color = -1
    private var matrixUniform = -1; private var modelUniform = -1
    private var width = 1; private var height = 1
    private var previewFailed = false
    fun update(identity: OcIdentity, scene: OcScene) {
        require(identity.route == "avatar_3d") { "请选择三维角色" }
        requested = OcIdentityRules.check(identity) to OcIdentityRules.scene(scene)
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        previewFailed = false; preview { initialize() }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { preview { resize(width, height) } }
    override fun onDrawFrame(gl: GL10?) { preview { draw() } }
    private inline fun preview(block: () -> Unit) {
        if (previewFailed) return
        try { block() } catch (failure: RuntimeException) { previewFailed = true; onPreviewFailure(failure) }
    }
    fun initialize() {
        program = linkProgram()
        position = GLES20.glGetAttribLocation(program, "aPosition")
        normal = GLES20.glGetAttribLocation(program, "aNormal")
        color = GLES20.glGetAttribLocation(program, "aColor")
        matrixUniform = GLES20.glGetUniformLocation(program, "uMvp")
        modelUniform = GLES20.glGetUniformLocation(program, "uModel")
        check(listOf(position, normal, color, matrixUniform, modelUniform).all { it >= 0 }) { "角色渲染器初始化失败" }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        drawn = null
    }
    fun resize(width: Int, height: Int) {
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, this.width, this.height)
    }
    fun draw() {
        val (identity, scene) = requested
        if (drawn != (identity to scene.motion)) {
            val mesh = AvatarMeshFactory.build(identity, scene.motion)
            meshBounds = bounds(mesh.vertices)
            val reusable = buffer?.takeIf { it.capacity() >= mesh.vertices.size } ?:
                ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            reusable.clear(); reusable.put(mesh.vertices); reusable.flip(); buffer = reusable
            count = mesh.vertexCount; drawn = identity to scene.motion
        }
        val bg = scene.background
        GLES20.glClearColor(((bg shr 16) and 255) / 255f, ((bg shr 8) and 255) / 255f, (bg and 255) / 255f, (bg ushr 24) / 255f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val model = FloatArray(16); val view = FloatArray(16); val projection = FloatArray(16)
        val mv = FloatArray(16); val mvp = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, scene.cameraYaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, scene.cameraPitch, 1f, 0f, 0f)
        val cameraBounds = projectedBounds(model, meshBounds)
        Matrix.scaleM(model, 0, scene.zoom, scene.zoom, scene.zoom)
        val cameraZ = maxOf(4f, cameraBounds[5] * scene.zoom + 2f)
        Matrix.setLookAtM(view, 0, 0f, 0f, cameraZ, 0f, 0f, 0f, 0f, 1f, 0f)
        val aspect = width.toFloat() / height
        var horizontal = (cameraBounds[3] - cameraBounds[0]) / 2f + .16f
        var vertical = (cameraBounds[4] - cameraBounds[1]) / 2f + .16f
        if (horizontal / vertical > aspect) vertical = horizontal / aspect else horizontal = vertical * aspect
        val centerX = (cameraBounds[0] + cameraBounds[3]) * scene.zoom / 2; val centerY = (cameraBounds[1] + cameraBounds[4]) * scene.zoom / 2
        val far = maxOf(4f, cameraZ - cameraBounds[2] * scene.zoom + 2f)
        Matrix.orthoM(projection, 0, centerX - horizontal, centerX + horizontal, centerY - vertical, centerY + vertical, .1f, far)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0); Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glUniformMatrix4fv(matrixUniform, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(modelUniform, 1, false, model, 0)
        val vertices = checkNotNull(buffer)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(normal); GLES20.glEnableVertexAttribArray(color)
        vertices.position(0); GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 40, vertices)
        vertices.position(3); GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 40, vertices)
        vertices.position(6); GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 40, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(normal); GLES20.glDisableVertexAttribArray(color)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "三维角色渲染失败" }
    }
    private fun bounds(vertices: FloatArray): FloatArray {
        val b = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        for (at in vertices.indices step 10) for (axis in 0..2) {
            b[axis] = minOf(b[axis], vertices[at + axis]); b[axis + 3] = maxOf(b[axis + 3], vertices[at + axis])
        }
        return b
    }
    private fun projectedBounds(matrix: FloatArray, b: FloatArray): FloatArray {
        val result = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val point = FloatArray(4); val projected = FloatArray(4)
        for (corner in 0..7) {
            point[0] = b[if ((corner and 1) == 0) 0 else 3]
            point[1] = b[if ((corner and 2) == 0) 1 else 4]
            point[2] = b[if ((corner and 4) == 0) 2 else 5]; point[3] = 1f
            Matrix.multiplyMV(projected, 0, matrix, 0, point, 0)
            for (axis in 0..2) {
                result[axis] = minOf(result[axis], projected[axis]); result[axis + 3] = maxOf(result[axis + 3], projected[axis])
            }
        }
        return result
    }
    fun release() { if (program != 0) GLES20.glDeleteProgram(program); program = 0; buffer = null; drawn = null }
    private fun linkProgram(): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMvp; uniform mat4 uModel;
            attribute vec3 aPosition; attribute vec3 aNormal; attribute vec4 aColor;
            varying vec4 vColor; varying vec3 vNormal;
            void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vColor=aColor; vNormal=mat3(uModel)*aNormal; }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float; varying vec4 vColor; varying vec3 vNormal;
            void main(){ float light=0.62+0.38*abs(dot(normalize(vNormal),normalize(vec3(-0.4,0.7,0.6))));
            gl_FragColor=vec4(vColor.rgb*light,vColor.a); }
        """.trimIndent())
        val created = GLES20.glCreateProgram()
        try {
            GLES20.glAttachShader(created, vertex); GLES20.glAttachShader(created, fragment); GLES20.glLinkProgram(created)
            val result = IntArray(1); GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, result, 0)
            check(result[0] == GLES20.GL_TRUE) { "三维角色着色器连接失败" }
            return created
        } catch (failure: Throwable) { GLES20.glDeleteProgram(created); throw failure }
        finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
    }
    private fun shader(kind: Int, text: String): Int {
        val id = GLES20.glCreateShader(kind)
        GLES20.glShaderSource(id, text); GLES20.glCompileShader(id)
        val result = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, result, 0)
        if (result[0] != GLES20.GL_TRUE) { GLES20.glDeleteShader(id); error("三维角色着色器编译失败") }
        return id
    }
}

/** Creates its own bounded pbuffer; never grabs the preview's GL context or pixels. */
object AvatarStillRenderer {
    fun png(identity: OcIdentity, scene: OcScene = OcScene(), width: Int = 768, height: Int = 1024): ByteArray {
        val bitmap = render(identity, scene, width, height)
        return try { ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }
    fun render(identity: OcIdentity, scene: OcScene, width: Int, height: Int): Bitmap {
        require(identity.route == "avatar_3d"); OcIdentityRules.check(identity); OcIdentityRules.scene(scene)
        require(width in 32..1536 && height in 32..1536 && width.toLong() * height <= 2_400_000)
        val previous = EglBinding.capture()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "本机三维渲染不可用" }
        val version = IntArray(2); check(EGL14.eglInitialize(display, version, 0, version, 1)) { "本机三维渲染初始化失败" }
        var surface = EGL14.EGL_NO_SURFACE; var context = EGL14.EGL_NO_CONTEXT
        val renderer = AvatarGlRenderer(identity, scene)
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
            val attrs = intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "本机三维画面格式不可用" }
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "本机三维上下文不可用" }
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) { "本机三维画布不可用" }
            renderer.initialize(); renderer.resize(width, height); renderer.draw(); GLES20.glFinish()
            val bytes = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "三维画面读取失败" }
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val at = (y * width + x) * 4
                val r = bytes.get(at).toInt() and 255; val g = bytes.get(at + 1).toInt() and 255
                val b = bytes.get(at + 2).toInt() and 255; val a = bytes.get(at + 3).toInt() and 255
                pixels[(height - 1 - y) * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            if (context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == context) renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // Android's EGL display lifetime is ref-counted. Balance only this initialization;
            // the GLSurfaceView's independent initialization remains alive.
            EGL14.eglTerminate(display)
            previous.restore()
        }
    }
    private data class EglBinding(val display: android.opengl.EGLDisplay, val context: android.opengl.EGLContext,
        val draw: android.opengl.EGLSurface, val read: android.opengl.EGLSurface) {
        fun restore() {
            if (context == EGL14.EGL_NO_CONTEXT) EGL14.eglReleaseThread()
            else check(EGL14.eglMakeCurrent(display, draw, read, context)) { "原三维预览上下文恢复失败" }
        }
        companion object {
            fun capture() = EglBinding(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentContext(),
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), EGL14.eglGetCurrentSurface(EGL14.EGL_READ))
        }
    }
}
