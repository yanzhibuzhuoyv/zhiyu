package com.zhiyu.app.data.callcamera

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.zhiyu.app.domain.ChatPhotoRules
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Only synthesized pixels. Never opens a camera, reads the user's photos or writes attachments. */
class CallCameraEncoderNativeTest {
    @Test fun entropyHeavyFrameIsJpegWithinBudgetWithoutExifOrUpsizing() {
        var random = 71123
        val pixels = IntArray(768 * 768) {
            random = random xor (random shl 13); random = random xor (random ushr 17)
            random = random xor (random shl 5)
            0xff000000.toInt() or (random and 0xffffff)
        }
        val payload = CallCameraEncoder().encode(CallCameraPixels(768, 768, pixels))
        assertEquals("image/jpeg", payload.mimeType)
        assertTrue(payload.byteCount in 1..384 * 1024)
        assertEquals(ChatPhotoRules.sha(payload.bytes), payload.normalizedSha256)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload.bytes, 0, payload.byteCount, bounds)
        assertEquals(768, bounds.outWidth)
        assertEquals(768, bounds.outHeight)
        val exif = ExifInterface(ByteArrayInputStream(payload.bytes))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
    }

    @Test fun portraitDerivativeRetainsCorrectDimensions() {
        val payload = CallCameraEncoder().encode(CallCameraPixels(2, 3, IntArray(6) { 0xff991122.toInt() }))
        val bitmap = BitmapFactory.decodeByteArray(payload.bytes, 0, payload.byteCount)
        try { assertEquals(2, bitmap.width); assertEquals(3, bitmap.height) }
        finally { bitmap.recycle() }
    }

    @Test fun malformedRasterIsRejectedBeforeAllocatingBitmap() {
        assertThrows(IllegalArgumentException::class.java) {
            CallCameraEncoder().encode(CallCameraPixels(769, 1, IntArray(769)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CallCameraEncoder().encode(CallCameraPixels(2, 2, IntArray(3)))
        }
    }
}
