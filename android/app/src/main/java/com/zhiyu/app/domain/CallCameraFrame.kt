package com.zhiyu.app.domain

import java.time.Instant

/** Ephemeral camera sample. Owners release their reference when the call/camera session ends. */
class CallCameraFrame(val photo: ChatPhotoPayload, val capturedAt: Instant, val elapsedRealtimeMillis: Long) {
    init {
        require(photo.mimeType == "image/jpeg" && photo.byteCount in 1..CallCameraFrameRules.MAX_BYTES &&
            elapsedRealtimeMillis >= 0) { "通话画面的类型、大小或采集时间无效" }
    }
    override fun toString() = "CallCameraFrame(capturedAt=$capturedAt, byteCount=${photo.byteCount})"
}

object CallCameraFrameRules {
    const val MAX_BYTES = 384 * 1024
    const val MAX_AGE_MILLIS = 10_000L
    /** Wall clock corrections must neither revive old pixels nor expire a fresh sample. */
    fun fresh(frame: CallCameraFrame, nowElapsedMillis: Long): Boolean =
        nowElapsedMillis >= frame.elapsedRealtimeMillis &&
            nowElapsedMillis - frame.elapsedRealtimeMillis <= MAX_AGE_MILLIS
}
