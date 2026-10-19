package com.zhiyu.app.ui

import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.CallCameraFrameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import android.os.SystemClock

/**
 * One active camera generation at a time. Frames are ephemeral: opening, clearing or switching
 * a lens invalidates every older callback and cancels the visual request still using an old lease.
 */
class CallCameraSession(private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    data class Lease(val token: Long, val frame: CallCameraFrame)

    private val lock = Any()
    private var version = 0L
    private var active = false
    private var latest: Lease? = null
    private var request: Job? = null

    /** Starts a new generation and returns the token its callbacks must carry. */
    fun open(): Long = synchronized(lock) {
        version++
        active = true
        latest = null
        request?.cancel()
        request = null
        version
    }

    /** Stops accepting frames and cancels a queued visual request without cancelling the call. */
    fun clear() = synchronized(lock) {
        active = false
        version++
        latest = null
        request?.cancel()
        request = null
    }

    /** The freshest unexpired frame, if any. Expiry uses monotonic capture age. */
    fun latest(): Lease? = synchronized(lock) {
        val lease = latest ?: return@synchronized null
        if (!CallCameraFrameRules.fresh(lease.frame, elapsed())) {
            latest = null
            null
        } else lease
    }

    /** Accepts only the current generation, a fresh frame and a frame not older than the latest. */
    fun offer(token: Long, frame: CallCameraFrame): Boolean = synchronized(lock) {
        if (!active || token != version || !CallCameraFrameRules.fresh(frame, elapsed())) return false
        val current = latest
        if (current != null && frame.elapsedRealtimeMillis < current.frame.elapsedRealtimeMillis) return false
        latest = Lease(token, frame)
        true
    }

    /**
     * Runs one visual request under a lease. Clearing/opening the camera cancels this caller's
     * visual-request job only; the call owner that launched it stays active.
     */
    suspend fun <T> withLease(lease: Lease, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job] ?: throw CancellationException("no camera request owner")
        val valid = synchronized(lock) {
            active && lease.token == version && latest == lease &&
                CallCameraFrameRules.fresh(lease.frame, elapsed())
        }
        if (!valid) throw CancellationException("camera frame is no longer current")
        synchronized(lock) {
            request?.cancel()
            request = job
        }
        try {
            return block()
        } finally {
            synchronized(lock) { if (request === job) request = null }
        }
    }
}

/** Adapts the UI session to the call layer without exposing its token or cancellation internals. */
internal class CameraSessionTurnSource(private val session: CallCameraSession) : com.zhiyu.app.data.duplex.CallCameraTurnSource {
    override fun currentFrame(): CallCameraFrame? = session.latest()?.frame

    override suspend fun <T> withFrame(frame: CallCameraFrame, block: suspend () -> T): T {
        val lease = session.latest()?.takeIf { it.frame === frame }
            ?: throw CancellationException("camera frame is no longer current")
        return session.withLease(lease) { block() }
    }
}
