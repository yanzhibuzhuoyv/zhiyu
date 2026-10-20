package com.zhiyu.app.ui

import android.os.SystemClock
import com.zhiyu.app.data.duplex.CallCameraCancelledException
import com.zhiyu.app.domain.CallCameraFrame
import com.zhiyu.app.domain.CallCameraFrameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference

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
    private val invalidations = mutableSetOf<(Long) -> Unit>()

    /** Starts a new generation and returns the token its callbacks must carry. */
    fun open(): Long = transition(true)

    /** Stops accepting frames and cancels a queued visual request without cancelling the call. */
    fun clear() { transition(false) }

    private fun transition(open: Boolean): Long {
        val changed = synchronized(lock) {
            val previous = version
            version++
            active = open
            latest = null
            request?.cancel(CallCameraCancelledException("Camera generation ended"))
            request = null
            Triple(version, previous, invalidations.toList())
        }
        changed.third.forEach { it(changed.second) }
        return changed.first
    }

    internal fun observeInvalidation(listener: (Long) -> Unit): AutoCloseable {
        synchronized(lock) { invalidations.add(listener) }
        return AutoCloseable { synchronized(lock) { invalidations.remove(listener) } }
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
        val context = currentCoroutineContext()
        val job = context[Job] ?: throw CancellationException("no camera request owner")
        context.ensureActive()
        synchronized(lock) {
            context.ensureActive()
            val valid = active && lease.token == version &&
                CallCameraFrameRules.fresh(lease.frame, elapsed())
            if (!valid) throw CallCameraCancelledException("Camera frame is no longer current")
            request?.cancel(CallCameraCancelledException("Camera request was superseded"))
            request = job
        }
        try {
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(lock) { if (request === job) request = null }
        }
    }
}

/** Adapts the UI session to the call layer without exposing its token or cancellation internals. */
internal class CameraSessionTurnSource(private val session: CallCameraSession) : com.zhiyu.app.data.duplex.CallCameraTurnSource {
    private val selected = AtomicReference<CallCameraSession.Lease?>()
    private val speaking = AtomicReference<CallCameraSession.Lease?>()
    override fun currentFrame(): CallCameraFrame? = session.latest().also { selected.set(it) }?.frame

    override fun observeInvalidation(listener: (CallCameraFrame) -> Unit): AutoCloseable {
        val observation = session.observeInvalidation { token ->
            speaking.get()?.takeIf { it.token == token }?.let {
                if (speaking.compareAndSet(it, null)) listener(it.frame)
            }
        }
        return AutoCloseable { observation.close(); speaking.set(null); selected.set(null) }
    }

    override suspend fun <T> withFrame(frame: CallCameraFrame, block: suspend () -> T): T {
        val lease = selected.getAndSet(null)?.takeIf { it.frame === frame }
            ?: throw CallCameraCancelledException("Camera frame is no longer current")
        speaking.set(lease)
        return session.withLease(lease) { block() }
    }
}
