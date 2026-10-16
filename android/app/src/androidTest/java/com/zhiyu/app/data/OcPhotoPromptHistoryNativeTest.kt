package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class OcPhotoPromptHistoryNativeTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        } } finally { bitmap.recycle() }
    }
    private fun message(attachment: MediaAttachment) = Message("a".repeat(32), "user", "", emptyList(),
        "2026-10-16T12:00:00+08:00", listOf(attachment))

    @Test fun sameReferenceIsRecognizedAsOwnOcWithoutPretendingATextModelHasVision() = runBlocking {
        NativeV4Context(base).use { context ->
            val bytes = png(0xff884466.toInt())
            val profile = LocalVisualRepository(context).import("a".repeat(32), bytes)
            val assets = LocalMediaStore(context)
            val snapshot = OcPhotoIdentitySnapshot.capture(profile, assets.details(checkNotNull(profile.referenceId)))
            val photos = ChatPhotoStore(context)
            val draft = photos.importBytes(bytes)
            val history = listOf(message(draft.attachment))
            val result = LocalPhotoPromptHistory(photos, OcPhotoIdentityContext(context))
                .build(history, "deepseek", "deepseek-v4-pro", ocIdentity = snapshot)
            assertTrue(result.evidence.contains("自己的设定形象"))
            assertEquals(0, result.messages.sumOf { it.photos.size })
            assertEquals(snapshot, result.ocIdentity)
            assertArrayEquals(bytes, assets.download(draft.attachment.id, draft.attachment.sha256))
            assertEquals("", history.single().content)
        }
    }

    @Test fun differentPhotoAddsASeparateBoundedReferenceAndSendsTwoRealImagePartsWithoutEditingOriginal() = runBlocking {
        NativeV4Context(base).use { context ->
            val referenceBytes = png(0xff884466.toInt()); val currentBytes = png(0xff2255aa.toInt())
            val profile = LocalVisualRepository(context).import("b".repeat(32), referenceBytes)
            val assets = LocalMediaStore(context)
            val snapshot = OcPhotoIdentitySnapshot.capture(profile, assets.details(checkNotNull(profile.referenceId)))
            val photos = ChatPhotoStore(context); val draft = photos.importBytes(currentBytes)
            val original = message(draft.attachment)
            val result = LocalPhotoPromptHistory(photos, OcPhotoIdentityContext(context))
                .build(listOf(original), "deepseek", "deepseek-flash", ocIdentity = snapshot)
            assertEquals(2, result.messages.sumOf { it.photos.size })
            assertTrue(result.messages.all { it.photos.size <= 1 })
            assertTrue(result.messages.sumOf { it.photos.sumOf(ChatPhotoPayload::byteCount) } <= ChatPhotoRules.MAX_UPLOAD_BYTES)
            assertEquals("", result.messages.last().content)
            assertTrue(result.evidence.contains("不确定"))
            var outgoing: JSONObject? = null
            DirectProviderClient { _, _, json -> outgoing = JSONObject(json)
                """{"choices":[{"finish_reason":"stop","message":{"content":"好"}}]}"""
            }.complete("deepseek", "synthetic-key", "deepseek-flash",
                listOf(DirectChatMessage("system", result.evidence)) + result.messages)
            val messages = checkNotNull(outgoing).getJSONArray("messages")
            assertEquals(3, messages.length())
            assertEquals("image_url", messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
            assertArrayEquals(currentBytes, assets.download(draft.attachment.id, draft.attachment.sha256))
            assertEquals("", original.content)
        }
    }

    @Test fun missingOptionalReferenceKeepsTheUserImageUsableAndDoesNotClaimVisualIdentity() = runBlocking {
        NativeV4Context(base).use { context ->
            val profile = LocalVisualRepository(context).import("c".repeat(32), png(0xff884466.toInt()))
            val assets = LocalMediaStore(context)
            val snapshot = OcPhotoIdentitySnapshot.capture(profile, assets.details(checkNotNull(profile.referenceId)))
            val photos = ChatPhotoStore(context); val draft = photos.importBytes(png(0xff2255aa.toInt()))
            val unavailable = OcPhotoIdentityContext({ throw IllegalStateException("synthetic missing reference") })
            val result = LocalPhotoPromptHistory(photos, unavailable).build(listOf(message(draft.attachment)),
                "deepseek", "deepseek-flash", ocIdentity = snapshot)
            assertEquals(1, result.messages.sumOf { it.photos.size })
            assertTrue(result.evidence.contains("不能假装"))
            assertFalse(result.evidence.contains("同一份原图"))
        }
    }
}
