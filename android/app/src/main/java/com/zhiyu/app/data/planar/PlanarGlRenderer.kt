package com.zhiyu.app.data.planar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.zhiyu.app.BuildConfig
import rig2d.planar.PlanarAnimation
import rig2d.planar.PlanarMixer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class PlanarFrame(val generation: Long, val number: Long, val parameters: Map<String, Float>)

/** All GPU allocation, upload, drawing and deletion run on the owning GL thread. */
class PlanarGlRenderer(
    private var document: PlanarDocument,
    private val onFailure: (RuntimeException) -> Unit = { throw it },
    private val onFrame: (PlanarFrame) -> Unit = {},
) : GLSurfaceView.Renderer {
    private data class Controls(val mouth: Float = 0f, val idle: Boolean = true)
    private data class Resource(val layer: PlanarLayer, val texture: Int, val positions: FloatBuffer,
        val uv: FloatBuffer, val indices: ShortBuffer, val indexCount: Int)

    @Volatile private var controls = Controls()
    @Volatile private var seconds = 0.0
    @Volatile private var stopped = false
    @Volatile private var clearColorArgb = 0
    private val pendingDocument = AtomicReference<PlanarDocument?>(null)
    private var resources = emptyList<Resource>()
    private var ownerContext = EGL14.EGL_NO_CONTEXT
    private var program = 0
    private var position = -1
    private var uv = -1
    private var viewport = -1
    private var offset = -1
    private var scale = -1
    private var texture = -1
    private var width = 1
    private var height = 1
    private var generation = 0L
    private var frame = 0L
    private var failed = false
    private var debugDrawStarted = false

    private fun trace(step:String) {
        if(BuildConfig.DEBUG)Log.d("ZhiyuPlanarState","renderer=${System.identityHashCode(this)} generation=$generation thread=${Thread.currentThread().name} $step")
    }

    fun update(mouth: Float, idle: Boolean) {
        require(mouth.isFinite() && mouth in 0f..1f) { "口型输入须为 0 到 1 之间的有限值" }
        controls = Controls(mouth, idle)
    }

    /** UI only publishes immutable data. GPU replacement runs at the next GL frame boundary. */
    fun submitDocument(next: PlanarDocument) {
        if (!stopped) pendingDocument.set(next)
    }

    /** Transparent by default for offscreen callers; applied only by the owning GL thread. */
    fun updateBackground(argb:Int) {clearColorArgb=argb}

    /** Time is supplied by the visible surface's loop, so time freezes while paused. */
    fun advanceTo(seconds: Double) {
        require(seconds.isFinite() && seconds >= 0.0) { "动画时间无效" }
        this.seconds = seconds
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        trace("surface-created")
        failed = false
        preview { initialize() }
        trace("surface-created-end failed=$failed")
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        trace("surface-changed size=${width}x$height")
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        preview { resize(width, height) }
    }
    override fun onDrawFrame(gl: GL10?) {
        if (pendingDocument.get() != null) failed = false
        preview { drawAt(seconds) }
    }

    private inline fun preview(block: () -> Unit) {
        if (stopped || failed) return
        try { block() } catch (failure: RuntimeException) {
            failed = true
            trace("failure type=${failure.javaClass.simpleName}")
            onFailure(failure)
        }
    }

    /** onSurfaceCreated means a new generation; old numeric IDs must never be deleted here. */
    fun initialize() {
        pendingDocument.getAndSet(null)?.let { document = it }
        trace("initialize-start layers=${document.layers.size}")
        check(!stopped) { "角色预览已关闭" }
        forgetGpu()
        ownerContext = EGL14.eglGetCurrentContext()
        check(ownerContext != EGL14.EGL_NO_CONTEXT) { "本机角色渲染上下文不可用" }
        require(document.width in 1..document.contract.maxSide && document.height in 1..document.contract.maxSide)
        require(document.layers.isNotEmpty() && document.layers.all { it.z.isFinite() && it.mesh.contract == document.contract }) {
            "角色图层或动画契约无效"
        }
        generation++
        frame = 0
        debugDrawStarted = false
        try {
            trace("program-start")
            program = linkProgram()
            trace("program-ready")
            position = GLES20.glGetAttribLocation(program, "aPosition")
            uv = GLES20.glGetAttribLocation(program, "aUv")
            viewport = GLES20.glGetUniformLocation(program, "uViewport")
            offset = GLES20.glGetUniformLocation(program, "uOffset")
            scale = GLES20.glGetUniformLocation(program, "uScale")
            texture = GLES20.glGetUniformLocation(program, "uTexture")
            check(listOf(position, uv, viewport, offset, scale, texture).all { it >= 0 }) { "角色着色器接口不可用" }
            for ((index,layer) in document.layers.sortedBy { it.z }.withIndex()) {
                trace("layer-upload-start index=$index")
                resources = resources + createResource(layer)
                trace("layer-upload-ready index=$index")
            }
            glCheck("角色纹理或网格上传失败")
            trace("initialize-ready")
        } catch (failure: Throwable) {
            deleteGpu()
            throw failure
        }
    }

    fun resize(width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, this.width, this.height)
    }

    fun drawAt(seconds: Double) {
        if (stopped) return
        pendingDocument.getAndSet(null)?.let { next ->
            trace("document-reload")
            deleteGpu()
            document = next
            failed = false
            initialize()
        }
        val debugFirst=BuildConfig.DEBUG&&!debugDrawStarted
        if(debugFirst){debugDrawStarted=true;trace("draw-start layers=${resources.size}")}
        require(seconds.isFinite() && seconds >= 0.0) { "动画时间无效" }
        check(program != 0 && ownerContext == EGL14.eglGetCurrentContext()) { "角色渲染上下文已改变" }
        val input = controls
        val parameters = PlanarAnimation.sample(document.contract, seconds, input.mouth, input.idle)
        // Validate the whole pose before drawing any layer. The portable mixer checks folds.
        val prepared = resources.map { it to PlanarMixer.vertices(it.layer.mesh, parameters) }
        if(debugFirst)trace("draw-pose-ready")
        prepareDraw()
        if(debugFirst)trace("draw-gpu-ready")
        for ((resource, vertices) in prepared) {
            resource.positions.clear(); resource.positions.put(vertices); resource.positions.flip()
            resource.uv.position(0); resource.indices.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, resource.positions)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, resource.uv)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resource.texture)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, resource.indexCount, GLES20.GL_UNSIGNED_SHORT, resource.indices)
        }
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(uv)
        glCheck("角色动态网格绘制失败")
        frame++
        if(debugFirst)trace("draw-ready frame=$frame")
        onFrame(PlanarFrame(generation, frame, Collections.unmodifiableMap(parameters.toMap())))
    }

    private fun prepareDraw() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glColorMask(true, true, true, true)
        val color=clearColorArgb
        val alpha=(color ushr 24)/255f
        GLES20.glClearColor(((color ushr 16) and 255)/255f*alpha,
            ((color ushr 8) and 255)/255f*alpha,(color and 255)/255f*alpha,alpha)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        // BitmapFactory produces premultiplied RGBA. Multiplying by SRC_ALPHA again darkens edges.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        val fit = minOf(width.toFloat() / document.width, height.toFloat() / document.height)
        GLES20.glUniform2f(viewport, width.toFloat(), height.toFloat())
        GLES20.glUniform2f(offset, (width - document.width * fit) / 2f, (height - document.height * fit) / 2f)
        GLES20.glUniform1f(scale, fit)
        GLES20.glUniform1i(texture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(uv)
    }

    private fun createResource(layer: PlanarLayer): Resource {
        val indices = layer.mesh.indices()
        require(indices.all { it in 0..65_535 }) { "角色网格超出手机索引范围" }
        val positions = floats(layer.mesh.baseVertices())
        val uv = floats(layer.mesh.uv())
        val indexBuffer = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indexBuffer.put(ShortArray(indices.size) { indices[it].toShort() }).flip()
        val id = uploadTexture(layer.texturePng)
        return Resource(layer, id, positions, uv, indexBuffer, indices.size)
    }

    private fun uploadTexture(png: ByteArray): Int {
        trace("texture-decode-start bytes=${png.size}")
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = true
            inScaled = false
        }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size, options)) { "角色纹理解码失败" }
        trace("texture-decode-ready size=${bitmap.width}x${bitmap.height}")
        val ids = IntArray(1)
        try {
            val max = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
            require(bitmap.width <= max[0] && bitmap.height <= max[0]) { "角色纹理超出本机尺寸限制" }
            GLES20.glGenTextures(1, ids, 0)
            check(ids[0] != 0) { "角色纹理显存不足" }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            // Row 0 and UV v=0 both denote the PNG top; the vertex shader flips source Y once.
            trace("texture-upload-gpu-start")
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            trace("texture-upload-gpu-ready")
            glCheck("角色纹理上传失败")
            return ids[0]
        } catch (failure: Throwable) {
            if (ids[0] != 0) GLES20.glDeleteTextures(1, ids, 0)
            throw failure
        } finally { bitmap.recycle() }
    }

    /** Stop is thread-safe; release must be queued on the GL thread. */
    fun stop() { stopped = true; pendingDocument.set(null) }
    fun release() { stop(); deleteGpu() }

    private fun deleteGpu() {
        if (ownerContext != EGL14.EGL_NO_CONTEXT && ownerContext == EGL14.eglGetCurrentContext()) {
            for (resource in resources) GLES20.glDeleteTextures(1, intArrayOf(resource.texture), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        }
        forgetGpu()
    }

    private fun forgetGpu() {
        resources = emptyList()
        program = 0
        ownerContext = EGL14.EGL_NO_CONTEXT
    }

    private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); flip() }

    private fun linkProgram(): Int {
        var vertex = 0
        var fragment = 0
        var result = 0
        try {
            vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            result = GLES20.glCreateProgram()
            check(result != 0) { "无法创建角色渲染程序" }
            GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "角色着色器连接失败" }
            return result
        } catch (failure: Throwable) {
            if (result != 0) GLES20.glDeleteProgram(result)
            throw failure
        } finally {
            if (vertex != 0) GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
        }
    }

    private fun shader(kind: Int, source: String): Int {
        val id = GLES20.glCreateShader(kind)
        check(id != 0) { "无法创建角色着色器" }
        GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            GLES20.glDeleteShader(id)
            error("角色着色器编译失败")
        }
        return id
    }

    private fun glCheck(message: String) {
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { message }
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec2 aPosition; attribute vec2 aUv;
            uniform vec2 uViewport; uniform vec2 uOffset; uniform float uScale;
            varying vec2 vUv;
            void main() {
                vec2 p = (aPosition * uScale + uOffset) / uViewport;
                gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
                vUv = aUv;
            }
        """
        const val FRAGMENT_SHADER = """
            precision mediump float; varying vec2 vUv; uniform sampler2D uTexture;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }
        """
    }
}
