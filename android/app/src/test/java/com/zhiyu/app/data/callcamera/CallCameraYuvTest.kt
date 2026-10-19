package com.zhiyu.app.data.callcamera

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CallCameraYuvTest {
    @Test fun respectsPlanePositionRowPaddingAndInterleavedChromaWithoutMovingBuffers() {
        val y = ByteBuffer.wrap(byteArrayOf(9, 9, 16, 235.toByte(), 9, 9, 81, 145.toByte(), 9, 9)).apply { position(2) }
        val uv = ByteBuffer.wrap(byteArrayOf(9, 128.toByte(), 9, 9)).apply { position(1) }
        val pixels = CallCameraYuv.convert(2, 2, CallCameraCrop(0, 0, 2, 2), 0,
            CallCameraPlane(y, 4, 1), CallCameraPlane(uv, 4, 2), CallCameraPlane(uv, 4, 2))
        assertEquals(2, pixels.width)
        assertEquals(2, pixels.height)
        assertEquals(0xff000000.toInt(), pixels.colors[0])
        assertEquals(0xffffffff.toInt(), pixels.colors[1])
        assertTrue((pixels.colors[2] and 255) < (pixels.colors[3] and 255))
        assertEquals(2, y.position())
        assertEquals(1, uv.position())
    }

    @Test fun rotatesClockwiseAndDoesNotMirrorTheFrontCameraUpload() {
        val y = plane(16, 48, 80, 112, 176, 235)
        val uv = plane(128, 128)
        val original = CallCameraYuv.convert(3, 2, CallCameraCrop(0, 0, 3, 2), 0,
            CallCameraPlane(y, 3, 1), CallCameraPlane(uv, 2, 1), CallCameraPlane(uv, 2, 1))
        val turned = CallCameraYuv.convert(3, 2, CallCameraCrop(0, 0, 3, 2), 90,
            CallCameraPlane(y, 3, 1), CallCameraPlane(uv, 2, 1), CallCameraPlane(uv, 2, 1))
        assertEquals(2, turned.width)
        assertEquals(3, turned.height)
        assertArrayEquals(intArrayOf(original.colors[3], original.colors[0], original.colors[4],
            original.colors[1], original.colors[5], original.colors[2]), turned.colors)
    }

    @Test fun honorsOddCropCoordinatesAndBoundsTheLongEdgeBeforeAllocatingPixels() {
        val full = plane(*IntArray(1200 * 800) { 128 })
        val uv = plane(*IntArray(600 * 400) { 128 })
        val pixels = CallCameraYuv.convert(1200, 800, CallCameraCrop(1, 1, 1200, 800), 270,
            CallCameraPlane(full, 1200, 1), CallCameraPlane(uv, 600, 1), CallCameraPlane(uv, 600, 1))
        assertEquals(768, pixels.height)
        assertTrue(pixels.width in 1..768)
        assertTrue(pixels.colors.size <= 768 * 768)
    }

    @Test fun allQuarterTurnsPreserveTheExactPixelSet() {
        val y = plane(16, 48, 80, 112, 176, 235)
        val uv = plane(128, 128)
        val results = listOf(0, 90, 180, 270).map { rotation -> CallCameraYuv.convert(3, 2,
            CallCameraCrop(0, 0, 3, 2), rotation, CallCameraPlane(y, 3, 1),
            CallCameraPlane(uv, 2, 1), CallCameraPlane(uv, 2, 1)).colors }
        results.forEach { assertArrayEquals(results.first().sortedArray(), it.sortedArray()) }
        assertArrayEquals(results.first().reversedArray(), results[2])
    }

    @Test fun rejectsTruncatedPlanesInvalidCropAndNonQuarterRotation() {
        val normal = CallCameraPlane(plane(128, 128, 128, 128), 2, 1)
        assertThrows(IllegalArgumentException::class.java) { CallCameraYuv.convert(2, 2,
            CallCameraCrop(0, 0, 2, 2), 0, normal.copy(buffer = plane(1)), normal, normal) }
        assertThrows(IllegalArgumentException::class.java) { CallCameraYuv.convert(2, 2,
            CallCameraCrop(-1, 0, 2, 2), 0, normal, normal, normal) }
        assertThrows(IllegalArgumentException::class.java) { CallCameraYuv.convert(2, 2,
            CallCameraCrop(0, 0, 2, 2), 45, normal, normal, normal) }
        assertThrows(IllegalArgumentException::class.java) { CallCameraYuv.convert(2, 2,
            CallCameraCrop(0, 0, 2, 2), 0, normal.copy(rowStride = Int.MAX_VALUE), normal, normal) }
    }

    private fun plane(vararg values: Int) = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })
}
