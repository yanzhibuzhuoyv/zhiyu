package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ChatPhotoNativeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun retainedOriginalAndSeparateDerivativeSurviveStoreRestartAndDraftFailure() {
        val original = jpegWithExif()
        val copy = original.copyOf()
        val store = ChatPhotoStore(context)
        val previous = store.readDraft()
        try {
            val draft = store.importBytes(original)
            store.saveDraft(draft)
            val payload = store.payload(draft.attachment)
            val derivative = BitmapFactory.decodeByteArray(payload.bytes, 0, payload.byteCount)
            try { assertTrue("EXIF rotation is applied to the derivative", derivative.height > derivative.width) }
            finally { derivative.recycle() }
            val exif = ExifInterface(ByteArrayInputStream(payload.bytes))
            assertNull("GPS must not be uploaded", exif.latLong)
            assertEquals(ChatPhotoRules.sha(original), draft.attachment.sha256)
            assertArrayEquals(copy, original)
            assertArrayEquals(original, LocalMediaStore(context).download(draft.attachment.id, draft.attachment.sha256))
            val restarted = ChatPhotoStore(context)
            assertEquals(draft, restarted.readDraft())
            assertArrayEquals(payload.bytes, restarted.payload(draft.attachment).bytes)
            assertFalse(LocalMediaStore(context).list("image").any { it.id == draft.attachment.id })
            // An unrelated finished send must not clear the currently selected photo draft.
            restarted.clearDraftIf(UUID.randomUUID().toString().replace("-", ""))
            assertEquals(draft, restarted.readDraft())
        } finally {
            store.clearDraftIf()
            previous?.let(store::saveDraft)
        }
    }

    @Test fun derivedOcrAndVisionArePersistedWithoutChangingOriginalImageOrLibrary() {
        val store = ChatPhotoStore(context)
        val draft = store.importBytes(jpegWithExif())
        val original = LocalMediaStore(context).download(draft.attachment.id, draft.attachment.sha256)
        val observations = listOf(ChatPhotoObservation(draft.attachment.sha256, "ocr", "我的生日是三月二日？"),
            ChatPhotoObservation(draft.attachment.sha256, "vision", "像是人物与六只翅膀，数量不确定"))
        store.saveObservations(draft.attachment, observations)
        assertEquals(observations, ChatPhotoStore(context).observations(draft.attachment))
        assertArrayEquals(original, LocalMediaStore(context).download(draft.attachment.id, draft.attachment.sha256))
        try { store.saveObservations(draft.attachment, listOf(ChatPhotoObservation("f".repeat(64), "ocr", "其他图片"))); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(observations, store.observations(draft.attachment))
    }

    @Test fun bundledChineseAndLatinOcrWorksOnChosenImageWithoutNetwork() = runBlocking {
        val bitmap = Bitmap.createBitmap(900, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("HELLO 2026", 35f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f })
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        val payload = ChatPhotoImageProcessor().normalize(output.toByteArray())
        val text = OcrChatPhotoReader().read(payload)
        assertTrue("Bundled OCR should read the synthetic visible year", text.contains("2026"))
        assertTrue(text.uppercase().contains("HELLO"))
    }

    private fun jpegWithExif(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 120, 220))
        bitmap.setPixel(0, 0, Color.rgb(UUID.randomUUID().hashCode() and 255, 30, 90))
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        bitmap.recycle()
        val file = File(context.cacheDir, "photo-fixture-${UUID.randomUUID()}.jpg")
        try {
            file.writeBytes(output.toByteArray())
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(30.123, 120.456)
                saveAttributes()
            }
            return file.readBytes()
        } finally { file.delete() }
    }
}
