package com.zhiyu.app.data.callcamera

/** Invalidates pending provider futures and posted frames on pause, switching lens or disposal. */
internal class CallCameraSessionGate {
    private var version = 0L
    private var active = false
    private var closed = false
    private var previousFrame: Long? = null

    @Synchronized fun begin(): Long? {
        if (closed || active) return null
        active = true
        previousFrame = null
        return ++version
    }

    @Synchronized fun live(token: Long): Boolean = active && !closed && token == version

    @Synchronized fun takeFrame(token: Long, now: Long): Boolean {
        if (!live(token)) return false
        val previous = previousFrame
        if (previous != null && now - previous < 1000) return false
        previousFrame = now
        return true
    }

    @Synchronized fun pause() { active = false; previousFrame = null; ++version }
    @Synchronized fun close() { closed = true; pause() }
}
