package com.zhiyu.app.data

import android.graphics.Bitmap
import com.zhiyu.app.data.local.qwen.QwenDecodedPng
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

class QwenDecodedPngNativeTest {
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
        } } finally { bitmap.recycle() }
    }
    @Test fun real512PngDecodesAndTruncatedOutputIsRejected() {
        val bytes = png(); val decoded = QwenDecodedPng.decode(bytes)
        try { assertEquals(512, decoded.width); assertEquals(512, decoded.height) } finally { decoded.recycle() }
        assertThrows(IllegalArgumentException::class.java) { QwenDecodedPng.decode(bytes.copyOf(bytes.size - 8)) }
    }
    @Test fun smallCompressedOutputWithHugeIhdrIsRejectedBeforePixelAllocation() {
        val bytes = png()
        ByteBuffer.wrap(bytes).putInt(16, 32000).putInt(20, 32000)
        val crc = CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        ByteBuffer.wrap(bytes).putInt(29, crc)
        assertTrue(bytes.size < 100000)
        assertThrows(IllegalArgumentException::class.java) { QwenDecodedPng.decode(bytes) }
    }
}
