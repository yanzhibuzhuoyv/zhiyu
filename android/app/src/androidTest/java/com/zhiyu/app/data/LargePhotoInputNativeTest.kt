package com.zhiyu.app.data

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.ui.screens.MediaDrawableDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Synthetic encoded originals; never allocate their full pixel plane to manufacture the fixture. */
class LargePhotoInputNativeTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun highEntropyOriginalOverFiveMiBIsRetainedAndOnlyDerivativeIsUploaded() {
        NativeV4Context(base).use { context ->
            val original = rgbPng(3072, 1024, noisy = true)
            assertTrue("The fixture must really exceed the old encoded limit", original.size > 5 * 1024 * 1024)
            val sha = ChatPhotoRules.sha(original)
            val store = ChatPhotoStore(context)
            val draft = store.importBytes(original)
            val payload = store.payload(draft.attachment)
            assertEquals(sha, draft.attachment.sha256)
            assertArrayEquals(original, LocalMediaStore(context).download(draft.attachment.id, sha))
            val display = MediaDrawableDecoder.decode(context.resources, original, sticker = false)
            assertTrue("Retained originals must still display via bounded decode", display.intrinsicWidth <= 1024)
            if (display is android.graphics.drawable.BitmapDrawable) display.bitmap.recycle()
            assertTrue(payload.byteCount <= ChatPhotoRules.MAX_UPLOAD_BYTES)
            assertEquals("image/jpeg", payload.mimeType)
            val bounds = bounds(payload.bytes)
            assertTrue("Wide images should retain readable detail, not become thumbnails", bounds.outWidth >= 2048)
            assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000)
            store.saveDraft(draft)
            assertEquals(draft, ChatPhotoStore(context).readDraft())
        }
    }

    @Test fun aboveTwentyMegapixelSourceIsBoundsSampledForBothChatAndOc() = runBlocking {
        NativeV4Context(base).use { context ->
            val original = rgbPng(12000, 1800, noisy = false)
            val sourceSha = ChatPhotoRules.sha(original)
            val chat = ChatPhotoStore(context).importBytes(original)
            assertArrayEquals(original, LocalMediaStore(context).download(chat.attachment.id, sourceSha))
            val upload = ChatPhotoStore(context).payload(chat.attachment)
            assertTrue(bounds(upload.bytes).outWidth <= 3072)
            val processor = LocalPortraitProcessor(context)
            val working = processor.manualStart(original)
            assertEquals(12000, working.sourceWidth)
            assertEquals(1800, working.sourceHeight)
            assertTrue(working.width <= LocalPortraitProcessor.MAX_WORK_EDGE)
            val profile = LocalVisualRepository(context).import("a".repeat(32), original)
            assertArrayEquals(original, LocalVisualRepository(context).download(profile.referenceId!!, sourceSha))
            assertEquals(sourceSha, ChatPhotoRules.sha(original))
        }
    }

    @Test fun transparentOriginalKeepsItsAlphaAfterLargeImageNormalization() {
        val bitmap = android.graphics.Bitmap.createBitmap(2400, 2400, android.graphics.Bitmap.Config.ARGB_8888)
        val source = try {
            bitmap.eraseColor(0x00ffffff)
            android.graphics.Canvas(bitmap).drawRect(500f, 500f, 1600f, 1600f,
                android.graphics.Paint().apply { color = 0x80774e90.toInt() })
            ByteArrayOutputStream().also { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val upload = ChatPhotoImageProcessor().normalize(source)
        assertEquals("image/png", upload.mimeType)
        val image = BitmapFactory.decodeByteArray(upload.bytes, 0, upload.byteCount)
        try {
            assertEquals(0, android.graphics.Color.alpha(image.getPixel(0, 0)))
            assertEquals(128, android.graphics.Color.alpha(image.getPixel(image.width / 2, image.height / 2)))
        }
        finally { image.recycle() }
    }

    @Test fun oversizedHighEntropyTransparentPngIsDownscaledWithoutFlattening() {
        val original = rgbPng(1800, 1800, noisy = true, alpha = true)
        assertTrue(original.size > ChatPhotoRules.MAX_UPLOAD_BYTES)
        val upload = ChatPhotoImageProcessor().normalize(original)
        assertEquals("image/png", upload.mimeType)
        assertTrue(upload.byteCount <= ChatPhotoRules.MAX_UPLOAD_BYTES)
        assertTrue("This exercises oversized PNG re-encoding, not JPEG compression", bounds(upload.bytes).outWidth < 1800)
        val decoded = BitmapFactory.decodeByteArray(upload.bytes, 0, upload.byteCount)
        try { assertTrue(decoded.hasAlpha()); assertTrue(android.graphics.Color.alpha(decoded.getPixel(0, 0)) < 255) }
        finally { decoded.recycle() }
    }

    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }

    private fun rgbPng(width: Int, height: Int, noisy: Boolean, alpha: Boolean = false): ByteArray {
        val compressed = ByteArrayOutputStream()
        var random = 94117
        DeflaterOutputStream(compressed).use { deflater ->
            val channels = if (alpha) 4 else 3
            val row = ByteArray(width * channels + 1)
            repeat(height) { y ->
                for (index in 1 until row.size) {
                    random = random xor (random shl 13); random = random xor (random ushr 17); random = random xor (random shl 5)
                    row[index] = if (alpha && index % 4 == 0) 128.toByte()
                        else if (noisy) random.toByte() else (40 + (index / 3 + y) % 7).toByte()
                }
                deflater.write(row)
            }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { data -> DataOutputStream(data).use {
                it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, (if (alpha) 6 else 2).toByte(), 0, 0, 0))
            } }.toByteArray()
            chunk(out, "IHDR", header); chunk(out, "IDAT", compressed.toByteArray()); chunk(out, "IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }

    private fun chunk(output: DataOutputStream, type: String, bytes: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size); output.write(name); output.write(bytes)
        val crc = CRC32().apply { update(name); update(bytes) }
        output.writeInt(crc.value.toInt())
    }
}
