package com.zhiyu.app.data.callcamera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.ChatPhotoPayload
import java.time.Instant

/** Owns every ImageProxy it receives, including throttled, failed and stopped-session frames. */
internal class CallCameraAnalyzer(private val gate: CallCameraSessionGate, private val token: Long,
    private val dispatch: (() -> Unit) -> Unit, private val onFrame: (CallCameraFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val encode: (ImageProxy) -> ChatPhotoPayload = CallCameraEncoder()::encode,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val now: () -> Instant = Instant::now) : ImageAnalysis.Analyzer {
    private var reportedError = false

    override fun analyze(image: ImageProxy) {
        try {
            val capturedElapsed = elapsed()
            if (gate.takeFrame(token, capturedElapsed)) {
                val capturedAt = now()
                val frame = CallCameraFrame(encode(image), capturedAt, capturedElapsed)
                reportedError = false
                dispatch { if (gate.live(token)) onFrame(frame) }
            }
        } catch (_: Exception) {
            if (!reportedError) {
                reportedError = true
                dispatch { if (gate.live(token)) onError("暂时没能读取摄像头画面，请关闭后重试") }
            }
        } finally { image.close() }
    }
}
