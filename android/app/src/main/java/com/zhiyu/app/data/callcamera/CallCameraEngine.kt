package com.zhiyu.app.data.callcamera

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zhiyu.app.domain.CallCameraFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal interface CallCameraEngine : AutoCloseable {
    @MainThread fun start()
    @MainThread fun stop()
    @MainThread override fun close()
}

internal fun interface CallCameraEngineFactory {
    fun create(context: Context, owner: LifecycleOwner, view: PreviewView, front: Boolean,
        onFrame: (CallCameraFrame) -> Unit, onError: (String) -> Unit): CallCameraEngine
}

/** Every binding owns its two use cases, worker and callback generation. Never calls unbindAll(). */
internal class CameraXCallCameraEngine(private val context: Context, private val owner: LifecycleOwner,
    private val view: PreviewView, private val front: Boolean,
    private val onFrame: (CallCameraFrame) -> Unit, private val onError: (String) -> Unit) : CallCameraEngine {
    private val gate = CallCameraSessionGate()
    private val main = ContextCompat.getMainExecutor(context)
    private var running: Running? = null

    override fun start() {
        val token = gate.begin() ?: return
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (gate.live(token)) {
                    try { bind(future.get(), token) }
                    catch (_: Exception) { failed(token, "摄像头暂时无法打开，可以关闭后重试") }
                }
            }, main)
        } catch (_: Exception) { failed(token, "摄像头暂时无法打开，可以关闭后重试") }
    }

    private fun bind(provider: ProcessCameraProvider, token: Long) {
        if (!gate.live(token)) return
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) { failed(token, "这个摄像头不可用，请切换另一个"); return }
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640, 480),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution)
            .setTargetRotation(rotation).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "zhiyu-call-camera") }
        val session = Running(provider, preview, analysis, executor)
        running = session
        preview.setSurfaceProvider(view.surfaceProvider)
        analysis.setAnalyzer(executor, CallCameraAnalyzer(gate, token, { main.execute(it) }, onFrame, onError))
        val camera = provider.bindToLifecycle(owner, selector, preview, analysis)
        session.watchRotation()
        // Last setup step: observing can synchronously fail and close this entire binding.
        session.watchCameraState(camera, token)
    }

    override fun stop() {
        gate.pause()
        val previous = running
        running = null
        try { previous?.close() }
        catch (_: Exception) { onError("摄像头关闭时遇到问题，请重新进入通话") }
    }

    override fun close() { gate.close(); stop() }

    private fun failed(token: Long, message: String) {
        if (!gate.live(token)) return
        stop()
        onError(message)
    }

    private inner class Running(val provider: ProcessCameraProvider, val preview: Preview,
        val analysis: ImageAnalysis, val executor: ExecutorService) : AutoCloseable {
        private val displays = context.getSystemService(DisplayManager::class.java)
        private var watching = false
        private var stateObservation: CallCameraStateObservation? = null
        private val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (view.display?.displayId == displayId) view.display?.rotation?.let {
                    preview.targetRotation = it
                    analysis.targetRotation = it
                }
            }
        }

        fun watchRotation() {
            displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            watching = displays != null
        }

        fun watchCameraState(camera: Camera, token: Long) {
            if (!gate.live(token) || running !== this) return
            val observation = CallCameraStateObservation(camera.cameraInfo.cameraState, owner,
                { gate.live(token) && running === this },
                { failed(token, "摄像头连接已中断，可以关闭后重试") })
            stateObservation = observation
            observation.start()
        }

        override fun close() {
            try {
                stateObservation?.close()
            } finally {
                try {
                    analysis.clearAnalyzer()
                    provider.unbind(preview, analysis)
                } finally {
                    try { if (watching) displays?.unregisterDisplayListener(listener) }
                    finally { executor.shutdownNow() }
                }
            }
        }
    }
}
