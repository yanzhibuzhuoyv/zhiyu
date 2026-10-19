package com.zhiyu.app.data.callcamera

import androidx.camera.core.ImageProxy
import com.zhiyu.app.domain.CallCameraFrame
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

class CallCameraAnalyzerNativeTest {
    @Test fun everyFrameIsClosedEvenWhenThrottledEncodingFailsOrTheCallHasEnded() {
        val gate = CallCameraSessionGate()
        val token = checkNotNull(gate.begin())
        var clock = 0L
        var closes = 0
        var encodes = 0
        var errors = 0
        val analyzer = CallCameraAnalyzer(gate, token, { it() }, {}, { errors++ },
            encode = { encodes++; throw IllegalArgumentException("synthetic failure") }, elapsed = { clock })
        repeat(2) { analyzer.analyze(frame { closes++ }) }
        clock = 1000
        analyzer.analyze(frame { closes++ })
        gate.close()
        clock = 2000
        analyzer.analyze(frame { closes++ })
        assertEquals(4, closes)
        assertEquals(2, encodes)
        assertEquals(1, errors)
    }

    @Test fun mainThreadDeliveryQueuedBeforePauseIsDiscardedAndANewSessionStartsFresh() {
        val gate = CallCameraSessionGate()
        val first = checkNotNull(gate.begin())
        val pending = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<CallCameraFrame>()
        var closes = 0
        var clock = 5000L
        val now = Instant.parse("2026-10-19T12:00:00Z")
        val photo = CallCameraEncoder().encode(CallCameraPixels(2, 2, IntArray(4) { 0xff991122.toInt() }))
        fun analyzer(token: Long) = CallCameraAnalyzer(gate, token, { pending.add(it) },
            { delivered.add(it) }, { fail(it) }, { photo }, { clock }, { now })
        analyzer(first).analyze(frame { closes++ })
        assertEquals(1, pending.size)
        gate.pause()
        pending.removeAt(0)()
        assertTrue(delivered.isEmpty())
        val second = checkNotNull(gate.begin())
        clock++
        analyzer(second).analyze(frame { closes++ })
        pending.removeAt(0)()
        assertEquals(1, delivered.size)
        assertEquals(5001L, delivered.single().elapsedRealtimeMillis)
        assertEquals(now, delivered.single().capturedAt)
        assertEquals(2, closes)
    }

    private fun frame(close: () -> Unit): ImageProxy = Proxy.newProxyInstance(ImageProxy::class.java.classLoader,
        arrayOf(ImageProxy::class.java)) { _, method, _ ->
        if (method.name == "close") { close(); null }
        else throw AssertionError("Synthetic frame pixels must not be accessed: ${method.name}")
    } as ImageProxy
}
