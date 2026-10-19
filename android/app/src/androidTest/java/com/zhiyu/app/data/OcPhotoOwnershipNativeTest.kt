package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class OcPhotoOwnershipNativeTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun picture(variant: Int = 0): ByteArray {
        val image = Bitmap.createBitmap(128, 192, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(128 * 192) { index ->
                val x = index % 128; val y = index / 128
                val red = (x * 3 + y + variant * 83) % 256
                val green = (x + y * 2 + variant * 31) % 256
                0xff000000.toInt() or (red shl 16) or (green shl 8) or ((x * 2 + y * 3) % 256)
            }
            image.setPixels(pixels, 0, 128, 0, 0, 128, 192)
            return ByteArrayOutputStream().use { out ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            }
        } finally { image.recycle() }
    }
    private fun user(id: Char, text: String, attachment: MediaAttachment? = null) = Message(id.toString().repeat(32),
        "user", text, emptyList(), "2026-10-19T09:00:00+08:00", listOfNotNull(attachment))
    private suspend fun snapshot(context: NativeV4Context, id: Char, bytes: ByteArray): OcPhotoIdentitySnapshot {
        val profile = LocalVisualRepository(context).import(id.toString().repeat(32), bytes)
        return checkNotNull(OcPhotoIdentitySnapshot.capture(profile,
            LocalMediaStore(context).details(checkNotNull(profile.referenceId))))
    }

    @Test fun sameDecodedWholePictureSurvivesLosslessReencodingButDifferentArtworkDoesNot() {
        val original = picture()
        val copiedEncoding = original + byteArrayOf(0, 0, 0, 0)
        assertNotEquals(ChatPhotoRules.sha(original), ChatPhotoRules.sha(copiedEncoding))
        assertTrue("文件摘要不同也必须认出完整相同像素", OcPhotoPixelMatch.same(original, copiedEncoding))
        assertFalse(OcPhotoPixelMatch.same(original, picture(1)))
        assertFalse(OcPhotoPixelMatch.same(original, byteArrayOf(1, 2, 3)))
    }

    @Test fun reencodedConfiguredSelfPortraitCarriesTrustedOwnershipIntoTheActualProviderPayload() = runBlocking {
        NativeV4Context(base).use { context ->
            val original = picture(); val snapshot = snapshot(context, 'a', original)
            val photos = ChatPhotoStore(context)
            val photo = photos.importBytes(original + byteArrayOf(0, 0, 0, 0)).attachment
            val raw = user('b', "这张图是谁？", photo)
            val prompt = LocalPhotoPromptHistory(photos, OcPhotoIdentityContext(context))
                .build(listOf(raw), "deepseek", "deepseek-flash", ocIdentity = snapshot)
            assertTrue(prompt.evidence.contains("\"ownership\":\"confirmed_self\""))
            assertTrue(prompt.evidence.contains("\"basis\":\"same_pixels\""))
            assertEquals(1, prompt.messages.sumOf { it.photos.size })
            var outgoing: JSONObject? = null
            DirectProviderClient { _, _, json -> outgoing = JSONObject(json)
                """{"choices":[{"finish_reason":"stop","message":{"content":"是我呀。"}}]}"""
            }.complete("deepseek", "synthetic-key", "deepseek-flash",
                listOf(DirectChatMessage("system", prompt.evidence)) + prompt.messages)
            val payload = checkNotNull(outgoing).getJSONArray("messages")
            assertTrue(payload.getJSONObject(0).getString("content").contains(photo.sha256))
            val parts = payload.getJSONObject(1).getJSONArray("content")
            assertEquals(raw.content, parts.getJSONObject(0).getString("text"))
            assertEquals(photos.payload(photo).dataUri(), parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertArrayEquals(original + byteArrayOf(0, 0, 0, 0), LocalMediaStore(context).download(photo.id, photo.sha256))
        }
    }

    @Test fun followingUserConfirmationIsRememberedForThatPictureAndCurrentOcOnly() = runBlocking {
        NativeV4Context(base).use { context ->
            val snapshot = snapshot(context, 'a', picture())
            val photos = ChatPhotoStore(context); val attachment = photos.importBytes(picture(1)).attachment
            val original = user('b', "", attachment)
            photos.saveObservations(attachment, listOf(ChatPhotoObservation(attachment.sha256, "vision", "可能是某个影视角色某某某")))
            val builder = LocalPhotoPromptHistory(photos, OcPhotoIdentityContext(context))
            val confirming = builder.build(listOf(original, user('c', "这是你，我给你画的")),
                "deepseek", "deepseek-v4-pro", ocIdentity = snapshot)
            assertNotNull("随后文字确认应关联最近用户附图", confirming.confirmation)
            assertTrue(confirming.evidence.contains("\"ownership\":\"confirmed_self\""))
            builder.remember(confirming)
            val reopened = LocalPhotoPromptHistory(ChatPhotoStore(context), OcPhotoIdentityContext(context))
            val remembered = reopened.build(listOf(user('d', "这是谁？", attachment)),
                "deepseek", "deepseek-v4-pro", ocIdentity = snapshot)
            assertTrue(remembered.evidence.contains("\"basis\":\"user_confirmed\""))
            assertTrue("模型的旧角色猜名不得胜过用户归属", remembered.evidence.contains("角色名猜测"))
            assertEquals(0, remembered.messages.sumOf { it.photos.size })
            assertEquals("", original.content)
            assertEquals("可能是某个影视角色某某某", photos.observations(attachment).single().text)
            val changedOc = snapshot(context, 'a', picture(2))
            val unknown = reopened.build(listOf(user('e', "这是谁？", attachment)),
                "deepseek", "deepseek-v4-pro", ocIdentity = changedOc)
            assertFalse("更换当前已确认形象后不能继承旧图归属", unknown.evidence.contains("\"ownership\":\"confirmed_self\""))
        }
    }

    @Test fun questionsNegationAndUnrelatedArtworkNeverBecomeConfirmedOwnership() = runBlocking {
        NativeV4Context(base).use { context ->
            val snapshot = snapshot(context, 'a', picture())
            val photos = ChatPhotoStore(context); val attachment = photos.importBytes(picture(1)).attachment
            val builder = LocalPhotoPromptHistory(photos, OcPhotoIdentityContext(context))
            for (text in listOf("这是你吗？", "这不是你，这是别人的自画像", "你的画像在哪儿？", "他说这是你，但我不这么认为")) {
                val result = builder.build(listOf(user('b', "", attachment), user('c', text)),
                    "deepseek", "deepseek-flash", ocIdentity = snapshot)
                assertNull("不应确认：$text", result.confirmation)
                assertFalse("不应强认陌生图：$text", result.evidence.contains("\"ownership\":\"confirmed_self\""))
                assertEquals(2, result.messages.sumOf { it.photos.size })
            }
        }
    }
}
