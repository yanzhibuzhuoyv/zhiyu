package com.zhiyu.app.data.callcamera

import org.junit.Assert.*
import org.junit.Test

class CallCameraSessionGateTest {
    @Test fun lateProviderAndFramesCannotRestartAStoppedSession() {
        val gate = CallCameraSessionGate()
        val first = checkNotNull(gate.begin())
        assertTrue(gate.live(first))
        assertNull(gate.begin())
        gate.pause()
        assertFalse(gate.live(first))
        val second = checkNotNull(gate.begin())
        assertNotEquals(first, second)
        assertFalse(gate.live(first))
        assertTrue(gate.live(second))
        gate.close()
        assertFalse(gate.live(second))
        assertNull(gate.begin())
    }

    @Test fun throttlesByMonotonicTimeAndAllowsFirstFrameAfterRestart() {
        val gate = CallCameraSessionGate()
        val first = checkNotNull(gate.begin())
        assertTrue(gate.takeFrame(first, 0))
        assertFalse(gate.takeFrame(first, 999))
        assertTrue(gate.takeFrame(first, 1000))
        assertFalse(gate.takeFrame(first, 1000))
        gate.pause()
        val second = checkNotNull(gate.begin())
        assertTrue(gate.takeFrame(second, 1001))
        assertFalse(gate.takeFrame(first, 3000))
    }
}
