package com.zhiyu.app.data.remotescene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Isolated private files and synthetic pixels only. No production Key or paid endpoint is used. */
class RemoteSceneCandidateNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pixels(): ByteArray {
        val image = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { image.eraseColor(0xff4278ab.toInt()); check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { image.recycle() }
    }
    private fun record(bytes: ByteArray) = RemoteSceneCandidate("a".repeat(32), "b".repeat(32), "c".repeat(64),
        ChatPhotoRules.sha(bytes), "image/png", "The same original OC at the window.", 0, "2026-10-14", "08:00", "d".repeat(64))
    @Test fun privateCandidateSurvivesReopenWithExactBytesAndNeverEntersMediaLibrary() {
        val directory = "remote_scene_test_" + UUID.randomUUID().toString().replace("-", "")
        val bytes = pixels(); val value = record(bytes)
        val library = LocalMediaStore(context).list("image", limit = 1000).map { it.id }
        try {
            val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
            bytes[bytes.lastIndex] = 0
            val reopened = RemoteSceneCandidateStore(context, directory)
            assertEquals(value, reopened.read(value.requestId)); val restored = reopened.bytes(value)
            assertEquals(value.imageSha, ChatPhotoRules.sha(restored))
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(restored, 0, restored.size))
            try { assertEquals(32, bitmap.width); assertEquals(48, bitmap.height) } finally { bitmap.recycle() }
            assertEquals(library, LocalMediaStore(context).list("image", limit = 1000).map { it.id })
        } finally { File(context.filesDir, directory).deleteRecursively() }
    }
    @Test fun shaMismatchBadRasterAndPathTraversalNeverCreateUsableCandidates() {
        val directory = "remote_scene_test_" + UUID.randomUUID().toString().replace("-", "")
        try {
            val store = RemoteSceneCandidateStore(context, directory); val bytes = pixels(); val value = record(bytes)
            assertThrows(IllegalArgumentException::class.java) { store.write(value.copy(imageSha = "f".repeat(64)), bytes) }
            assertThrows(IllegalArgumentException::class.java) { store.write(record(byteArrayOf(1, 2)), byteArrayOf(1, 2)) }
            assertThrows(IllegalArgumentException::class.java) { store.read("../outside") }
            assertNull(store.read(value.requestId))
        } finally { File(context.filesDir, directory).deleteRecursively() }
    }
}
