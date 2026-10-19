package com.zhiyu.app.ui

import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CallCameraSessionTest {
    private fun frame(at: Long, value: Byte = 1): CallCameraFrame {
        val bytes = byteArrayOf(-1, -40, -1, value)
        val sha = ChatPhotoRules.sha(bytes)
        return CallCameraFrame(ChatPhotoPayload(sha, sha, "image/jpeg", bytes), Instant.EPOCH, at)
    }

    @Test fun oldCallbacksCannotRestorePixelsAfterCloseOrLensChange() {
        var now = 1000L
        val session = CallCameraSession { now }
        val old = session.open()
        assertTrue(session.offer(old, frame(now)))
        assertNotNull(session.latest())
        val fresh = session.open()
        assertNull(session.latest())
        assertFalse(session.offer(old, frame(now)))
        assertTrue(session.offer(fresh, frame(now, 2)))
        assertEquals(2.toByte(), session.latest()!!.frame.photo.bytes.last())
        session.clear()
        assertNull(session.latest())
        assertFalse(session.offer(fresh, frame(now)))
        val again = session.open()
        now += 20_000
        assertFalse(session.offer(again, frame(1000)))
        assertFalse(session.offer(again, frame(now + 1)))
    }

    @Test fun freshestFrameWinsAndExpiresUsingMonotonicTime() {
        var now = 1000L
        val session = CallCameraSession { now }
        val token = session.open()
        assertTrue(session.offer(token, frame(now, 1)))
        now = 2000
        assertTrue(session.offer(token, frame(now, 2)))
        assertFalse(session.offer(token, frame(1500, 3)))
        assertEquals(2.toByte(), session.latest()!!.frame.photo.bytes.last())
        now = 12_001
        assertNull(session.latest())
    }

    @Test fun disablingCameraCancelsItsPendingVisualRequestWithoutCancellingTheCallOwner() = runTest {
        val session = CallCameraSession { 1000 }
        val token = session.open()
        session.offer(token, frame(1000))
        val lease = session.latest()!!
        var completed = false
        val request = launch { session.withLease(lease) { awaitCancellation(); completed = true } }
        runCurrent()
        session.clear()
        advanceUntilIdle()
        assertTrue(request.isCancelled)
        assertFalse(completed)
        assertTrue(currentCoroutineContext().isActive)
        assertNull(session.latest())
    }

    @Test fun aLateRequestCannotUseAnOldLeaseAfterReopeningCamera() = runTest {
        val session = CallCameraSession { 1000 }
        val old = session.open(); session.offer(old, frame(1000))
        val lease = session.latest()!!
        session.clear(); session.open()
        var calls = 0
        try { session.withLease(lease) { calls++ }; fail() } catch (_: CancellationException) { }
        assertEquals(0, calls)
    }
}
