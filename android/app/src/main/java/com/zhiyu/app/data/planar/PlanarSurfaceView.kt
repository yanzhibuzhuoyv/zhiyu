package com.zhiyu.app.data.planar

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View

/** At most 30 idle frames per second, with no repeating requests while hidden or paused. */
class PlanarSurfaceView(
    context: Context,
    document: PlanarDocument,
    onFailure: (RuntimeException) -> Unit = {},
    onFrame: (PlanarFrame) -> Unit = {},
) : GLSurfaceView(context) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var released = false
    @Volatile private var drawingEnabled = false
    @Volatile private var callbackEpoch = 0L
    private var configured = false
    private var attached = false
    private var everAttached = false
    private var lifecycleResumed = false
    private var glPaused = true
    private var mouth = 0f
    private var idle = true
    private var failed = false
    private var loopPosted = false
    private var elapsed = 0.0
    private var lastTick = 0L
    private val renderer = PlanarGlRenderer(document,
        onFailure = { failure ->
            val epoch = callbackEpoch
            main.post {
                if (!released && epoch == callbackEpoch) {
                    failed = true
                    stopLoop()
                    onFailure(failure)
                }
            }
        },
        onFrame = { frame ->
            val epoch = callbackEpoch
            main.post {
                if (!released && drawingEnabled && epoch == callbackEpoch) {
                    failed = false
                    onFrame(frame)
                }
            }
        })
    private val tick = object : Runnable {
        override fun run() {
            loopPosted = false
            if (!shouldLoop()) return
            val now = SystemClock.uptimeMillis()
            elapsed += (now - lastTick).coerceAtLeast(0L) / 1000.0
            lastTick = now
            renderer.advanceTo(elapsed)
            requestRender()
            startLoop()
        }
    }

    init {
        contentDescription = "二维人物动态预览"
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        // Recreate after pause to release phone GPU memory. IDs are rebuilt for every generation.
        preserveEGLContextOnPause = false
        super.onPause()
        configured = true
    }

    val isAnimationRunning: Boolean get() = loopPosted

    fun update(mouth: Float, idle: Boolean) {
        requireMainThread()
        if (released) return
        require(mouth.isFinite() && mouth in 0f..1f) { "口型输入须为 0 到 1 之间的有限值" }
        val changed = this.mouth != mouth || this.idle != idle
        this.mouth = mouth
        this.idle = idle
        renderer.update(mouth, idle)
        if (changed && drawingEnabled) requestRender()
        if (shouldLoop()) startLoop() else stopLoop()
    }

    fun resumePreview() {
        requireMainThread()
        if (released) return
        lifecycleResumed = true
        syncSurface()
    }

    fun pausePreview() {
        requireMainThread()
        lifecycleResumed = false
        syncSurface()
    }

    fun releasePreview() {
        requireMainThread()
        if (released) return
        released = true
        callbackEpoch++
        drawingEnabled = false
        stopLoop()
        renderer.stop()
        queueEvent { renderer.release() }
        // EGL destruction owns any resources whose context was already paused or lost.
        if (!glPaused) { super.onPause(); glPaused = true }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (everAttached) glPaused = false // GLSurfaceView creates an initially unpaused GL thread.
        everAttached = true
        attached = true
        syncSurface()
    }

    override fun onDetachedFromWindow() {
        attached = false
        syncSurface()
        super.onDetachedFromWindow()
        glPaused = true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (configured) syncSurface()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (configured) syncSurface()
    }

    private fun syncSurface() {
        if (!configured) return
        val active = !released && lifecycleResumed && attached && windowVisibility == VISIBLE && isShown
        if (active != drawingEnabled) callbackEpoch++
        drawingEnabled = active
        if (active && glPaused) {
            failed = false
            glPaused = false
            lastTick = SystemClock.uptimeMillis()
            super.onResume()
            requestRender()
        } else if (!active && !glPaused) {
            stopLoop()
            super.onPause()
            glPaused = true
        }
        if (shouldLoop()) startLoop() else stopLoop()
    }

    private fun shouldLoop() = drawingEnabled && idle && !failed && !released

    private fun startLoop() {
        if (loopPosted || !shouldLoop()) return
        if (lastTick == 0L) lastTick = SystemClock.uptimeMillis()
        loopPosted = true
        main.postDelayed(tick, FRAME_DELAY_MS)
    }

    private fun stopLoop() {
        main.removeCallbacks(tick)
        loopPosted = false
        lastTick = 0L
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "预览生命周期须在主线程操作" }
    }

    private companion object { const val FRAME_DELAY_MS = 33L }
}
