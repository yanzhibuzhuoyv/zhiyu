package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CallCameraFrameTest {
    private fun photo(size: Int = 32, jpeg: Boolean = true): ChatPhotoPayload {
        val bytes = ByteArray(size)
        val magic = if (jpeg) byteArrayOf(-1, -40, -1) else byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        magic.copyInto(bytes)
        val hash = ChatPhotoRules.sha(bytes)
        return ChatPhotoPayload(hash, hash, if (jpeg) "image/jpeg" else "image/png", bytes)
    }

    @Test fun freshUsesOnlyMonotonicCaptureAgeAndRejectsFutureOrRebootedTimes() {
        val frame = CallCameraFrame(photo(), Instant.parse("2000-01-01T00:00:00Z"), 12_000)
        assertTrue(CallCameraFrameRules.fresh(frame, 12_000))
        assertTrue(CallCameraFrameRules.fresh(frame, 22_000))
        assertFalse(CallCameraFrameRules.fresh(frame, 22_001))
        assertFalse(CallCameraFrameRules.fresh(frame, 11_999))
        assertFalse(CallCameraFrameRules.fresh(frame, -1))
        assertFalse(CallCameraFrameRules.fresh(frame, Long.MAX_VALUE))
    }

    @Test fun cameraPayloadIsSmallJpegAndDoesNotExposePixelsInLogs() {
        val frame = CallCameraFrame(photo(CallCameraFrameRules.MAX_BYTES), Instant.EPOCH, 0)
        assertEquals(384 * 1024, frame.photo.byteCount)
        assertFalse(frame.toString().contains(frame.photo.dataUri()))
        assertFalse(frame.toString().contains("base64"))
        val invalid = listOf(
            { CallCameraFrame(photo(jpeg = false), Instant.EPOCH, 0) },
            { CallCameraFrame(photo(CallCameraFrameRules.MAX_BYTES + 1), Instant.EPOCH, 0) },
            { CallCameraFrame(photo(), Instant.EPOCH, -1) },
        )
        invalid.forEach { action -> try { action(); fail("Invalid camera input must fail") } catch (_: IllegalArgumentException) { } }
    }
}
