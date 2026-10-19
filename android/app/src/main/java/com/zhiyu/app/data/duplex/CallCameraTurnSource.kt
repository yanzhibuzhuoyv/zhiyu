package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.CallCameraFrame

/**
 * A single camera frame may accompany one final call utterance. Implementations must reject a
 * frame that is no longer current and cancel the collecting reply when the camera is closed.
 */
interface CallCameraTurnSource {
    fun currentFrame(): CallCameraFrame?
    suspend fun <T> withFrame(frame: CallCameraFrame, block: suspend () -> T): T
}
