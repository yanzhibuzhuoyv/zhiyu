package com.zhiyu.app.data.duplex

import com.zhiyu.app.domain.CallCameraFrame
import kotlinx.coroutines.CancellationException

/** Dedicated cancellation for camera generations that were invalidated while a reply was in flight. */
class CallCameraCancelledException(message: String = "Call camera request is no longer current") :
    CancellationException(message)

/**
 * A single camera frame may accompany one final call utterance. Implementations must reject a
 * frame that is no longer current and cancel the collecting reply when the camera is closed.
 */
interface CallCameraTurnSource {
    fun currentFrame(): CallCameraFrame?
    suspend fun <T> withFrame(frame: CallCameraFrame, block: suspend () -> T): T
    /** Covers queued speech too, after the network producer has finished. */
    fun observeInvalidation(listener: (CallCameraFrame) -> Unit): AutoCloseable = AutoCloseable {}
}
