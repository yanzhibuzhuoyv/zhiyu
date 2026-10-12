package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.local.qwen.QwenScenePromptClient
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Real bitmap preprocessing and official JSON serialization; no network or user's credential store. */
class QwenScenePromptNativeTest {
    private class Store : CredentialStore {
        private var connection = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-key", "deepseek-flash")
        private var binding = CompanionBinding("deepseek", "deepseek-flash")
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() = error("Synthetic test never logs out")
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class Transport : DirectProviderTransport {
        var calls = 0; var request = JSONObject()
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            assertEquals("https://api.deepseek.com/chat/completions", endpoint)
            assertEquals("synthetic-key", key)
            calls++; request = JSONObject(json)
            val answer = "{\"approved\":true,\"reason\":\"合成传输测试，不是实际视觉模型批准\",\"correction\":\"\"}"
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", answer)))).toString()
        }
    }
    private val metadata = QwenScenePromptRequest("a".repeat(32), "2026-10-12", "b".repeat(32),
        "18:30", "准备晚餐", listOf("food", "evening"))
    private val prompt = "A dinner table in warm evening light."

    @Test fun actualCompositeAndReferencePixelsGoThroughTheNativeVisionProviderProtocol() = runBlocking {
        val transport = Transport()
        val reference = png(64, 128, 0xff8a5094.toInt())
        val candidate = png(512, 512, 0xff639978.toInt())
        val helper = QwenScenePromptClient(Store(), DirectProviderClient(transport))
        val result = helper.reviewComposite(metadata.copy(referenceSha256 = ChatPhotoRules.sha(reference)), prompt, candidate, reference)
        assertEquals(QwenSceneReviewStatus.APPROVED, result.status)
        assertEquals(QwenSceneReviewPurpose.COMPOSITE, result.purpose)
        assertFalse(result.identityVerified)
        assertEquals(ChatPhotoRules.sha(candidate), result.imageSha256)
        assertEquals(1, transport.calls)
        val photos = photos(transport.request)
        assertEquals(2, photos.size)
        assertPixels(photos[0], 0xff8a5094.toInt(), 64, 128)
        assertPixels(photos[1], 0xff639978.toInt(), 512, 512)
    }

    @Test fun backgroundGateReceivesOneActualPngAndAPlainlyDifferentBackgroundConstraint() = runBlocking {
        val transport = Transport()
        val candidate = png(512, 512, 0xff748bc2.toInt())
        val result = QwenScenePromptClient(Store(), DirectProviderClient(transport)).review(metadata, prompt, candidate)
        assertEquals(QwenSceneReviewPurpose.BACKGROUND, result.purpose)
        val messages = transport.request.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("reject any person"))
        assertEquals(1, photos(transport.request).size)
        assertPixels(photos(transport.request).single(), 0xff748bc2.toInt(), 512, 512)
    }

    private fun photos(request: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val messages = request.getJSONArray("messages")
        repeat(messages.length()) { index ->
            val parts = messages.getJSONObject(index).optJSONArray("content") ?: return@repeat
            repeat(parts.length()) { part ->
                val value = parts.getJSONObject(part)
                if (value.optString("type") == "image_url") result.add(value.getJSONObject("image_url"))
            }
        }
        return result
    }
    private fun assertPixels(image: JSONObject, color: Int, width: Int, height: Int) {
        assertEquals("high", image.getString("detail"))
        val bytes = Base64.getDecoder().decode(image.getString("url").substringAfter(","))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(width, bitmap.width); assertEquals(height, bitmap.height)
            val actual = bitmap.getPixel(width / 2, height / 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(actual)) <= 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.green(actual)) <= 2)
            assertTrue(kotlin.math.abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(actual)) <= 2)
        } finally { bitmap.recycle() }
    }
    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
}
