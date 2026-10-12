package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

class PhotoInputRulesTest {
    @Test fun zeroLengthBulkReadsCannotTurnAChosenImageIntoAnEmptyDraftOrSpinForever() {
        val stream = object : InputStream() {
            var offset = 0
            override fun read(bytes: ByteArray, start: Int, length: Int) = if (offset == 3) -1 else 0
            override fun read() = if (offset == 3) -1 else intArrayOf(7, 11, 19)[offset++]
        }
        assertArrayEquals(byteArrayOf(7, 11, 19), PhotoInputRules.boundedBytes(stream))
    }

    @Test fun largePortraitAndPanoramaBothStayWithinUploadPixelAndEdgeBudgets() {
        listOf(12000 to 1800, 4800 to 4800, 9000 to 8000, 1 to 32768).forEach { (width, height) ->
            val (x, y) = PhotoInputRules.uploadSize(width, height)
            assertTrue(x in 1..3072 && y in 1..3072)
            assertTrue(x.toLong() * y <= 4_000_000)
            assertTrue(x <= width && y <= height)
        }
        assertEquals(3072 to 1024, PhotoInputRules.uploadSize(3072, 1024))
    }

    @Test fun tinyAndAlreadyReadableImagesAreNotUpscaled() {
        assertEquals(64 to 32, PhotoInputRules.uploadSize(64, 32))
        assertEquals(1600 to 1000, PhotoInputRules.uploadSize(1600, 1000))
    }

    @Test fun legacyDecodeSamplesBeforeAllocationAndRejectsOverflowDimensions() {
        val width = 12000; val height = 1800
        val sample = PhotoInputRules.sampleSize(width, height, 1536, 4_000_000)
        assertEquals(8, sample)
        assertTrue(((width + sample - 1) / sample).toLong() * ((height + sample - 1) / sample) <= 4_000_000)
        listOf(-1 to 1, 0 to 1, 32769 to 1, 32768 to 32768, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (x, y) ->
            try { PhotoInputRules.uploadSize(x, y); fail("Out-of-policy source dimensions must fail before allocation") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
