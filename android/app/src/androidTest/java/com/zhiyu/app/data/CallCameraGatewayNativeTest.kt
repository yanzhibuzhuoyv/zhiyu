package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.stream.StreamingDirectProviderTransport
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Real request JSON and isolated SQLite, no camera, model, Key, permissions or network. */
class CallCameraGatewayNativeTest {
    @Test fun currentFrameIsSerializedOnceAndNeverStoredOrReusedInLaterCalls(): Unit = runBlocking {
        fixture { repo, database, transport, chat, context ->
            val before = files(context)
            val frame = frame()
            val pair = repo.sendCallVisualMessage(chat.id, "看我手里这个", frame) { }
            val messages = transport.requests.single().getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains("<call_camera_observation>"))
            assertTrue(system.contains(frame.capturedAt.toString()))
            assertTrue(system.contains("不是恋人OC"))
            assertTrue(system.contains("低信任"))
            val users = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.getString("role") == "user" }
            val latest = users.last().getJSONArray("content")
            assertEquals(2, latest.length())
            assertTrue(latest.getJSONObject(0).getString("text").endsWith("看我手里这个"))
            assertEquals(frame.photo.dataUri(), latest.getJSONObject(1).getJSONObject("image_url").getString("url"))
            val images = (0 until messages.length()).sumOf { index ->
                val parts = messages.getJSONObject(index).optJSONArray("content")
                if (parts == null) 0 else (0 until parts.length()).count { parts.getJSONObject(it).optString("type") == "image_url" }
            }
            assertEquals(1, images)
            assertEquals("看我手里这个", pair.first.content)
            assertEquals("call_user", pair.first.origin)
            assertEquals("call_reply", pair.second.origin)
            assertTrue(pair.first.mediaAttachments.isEmpty())
            assertTrue(pair.second.mediaAttachments.isEmpty())
            assertTrue(repo.history(chat.id).messages.isEmpty())
            assertEquals(pair.first.content, database.recognitionOriginal(owner(repo), chat.id, pair.first.id)?.text)
            assertEquals(before, files(context))
            repo.sendCallMessage(chat.id, "接着聊") { }
            val later = transport.requests.last().toString()
            assertFalse(later.contains("image_url"))
            assertFalse(later.contains(frame.photo.dataUri()))
            assertFalse(later.contains("call_camera_observation"))
            assertTrue(later.contains("call_camera_unavailable"))
        }
    }

    @Test fun clockQuestionWithCameraStillReachesTheVisualProvider(): Unit = runBlocking {
        fixture { repo, _, transport, chat, _ ->
            repo.sendCallVisualMessage(chat.id, "现在几点", frame()) { }
            assertEquals(1, transport.requests.size)
            assertTrue(transport.requests.single().toString().contains("image_url"))
        }
    }

    @Test fun existingOcAndOldUserPhotoAreNeverSentAsTheCurrentCamera(): Unit = runBlocking {
        fixture { repo, database, transport, chat, context ->
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            val png = try { java.io.ByteArrayOutputStream().use { out ->
                bitmap.eraseColor(0xff884466.toInt())
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            } } finally { bitmap.recycle() }
            val profile = LocalVisualRepository(context).import(chat.id, png)
            val photos = ChatPhotoStore(context)
            val old = photos.importBytes(png).attachment
            database.append(owner(repo), chat.id, "user", "这是你之前的照片", attachments = listOf(old))
            val before = files(context)
            val frame = frame()
            repo.sendCallVisualMessage(chat.id, "看我现在手里的东西", frame) { }
            val wire = transport.requests.single().toString()
            assertTrue(wire.contains("不是恋人OC"))
            val urls = transport.requests.single().getJSONArray("messages").let { messages ->
                (0 until messages.length()).flatMap { index ->
                    val parts = messages.getJSONObject(index).optJSONArray("content") ?: return@flatMap emptyList()
                    (0 until parts.length()).mapNotNull { part ->
                        parts.getJSONObject(part).optJSONObject("image_url")?.optString("url")
                    }
                }
            }
            assertFalse(urls.contains(photos.payload(old).dataUri()))
            assertTrue(urls.contains(frame.photo.dataUri()))
            assertEquals(before, files(context))
            assertEquals(profile, LocalVisualRepository(context).profile(chat.id))
            assertTrue(photos.observations(old).isEmpty())
            assertEquals(listOf(old), repo.history(chat.id).messages.single().mediaAttachments)
        }
    }

    @Test fun unsupportedModelIsExplicitlyRejectedWithoutPretendingOrSavingAnOriginal(): Unit = runBlocking {
        fixture(model = "deepseek-v4-pro") { repo, database, transport, chat, _ ->
            try { repo.sendCallVisualMessage(chat.id, "你看到什么", frame()) { }; fail("Unsupported visual call must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("画面")) }
            assertTrue(transport.requests.isEmpty())
            assertTrue(database.history(owner(repo), chat.id, includeCalls = true).messages.isEmpty())
        }
    }

    @Test fun staleFrameFailsBeforeSubmittingAndDoesNotFallBackToOcr(): Unit = runBlocking {
        fixture(nowElapsed = { 11_001L }) { repo, database, transport, chat, _ ->
            try { repo.sendCallVisualMessage(chat.id, "看一下", frame()) { }; fail("Stale frame must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("画面")) }
            assertTrue(transport.requests.isEmpty())
            assertTrue(database.history(owner(repo), chat.id, includeCalls = true).messages.isEmpty())
        }
    }

    @Test fun frameThatExpiresDuringPreparationCannotBeSent(): Unit = runBlocking {
        var reads = 0
        fixture(nowElapsed = { if (++reads == 1) 1_000L else 11_001L }) { repo, database, transport, chat, _ ->
            try { repo.sendCallVisualMessage(chat.id, "再看看", frame()) { }; fail("Expired queued image must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("画面")) }
            assertTrue(transport.requests.isEmpty())
            val rows = database.history(owner(repo), chat.id, includeCalls = true).messages
            assertTrue(rows.all { it.role == "user" && it.content == "再看看" && it.mediaAttachments.isEmpty() })
        }
    }

    private fun frame(): CallCameraFrame {
        val bytes = byteArrayOf(-1, -40, -1, 1, 2, 3, 4)
        val hash = ChatPhotoRules.sha(bytes)
        return CallCameraFrame(ChatPhotoPayload(hash, hash, "image/jpeg", bytes), Instant.parse("2026-10-19T12:00:00Z"), 1_000)
    }
    private suspend fun owner(repo: LocalChatRepository): String = checkNotNull(repo.savedBinding()).let {
        "companion-" + LocalMemoryCodec.sha("${it.providerId}\u0000${it.model}".toByteArray())
    }
    private fun files(context: NativeV4Context): Set<String> = context.filesDir.walkTopDown()
        .filter { it.isFile && !it.toRelativeString(context.filesDir).startsWith("databases") }
        .map { it.toRelativeString(context.filesDir) }.toSet()

    private class Store : CredentialStore {
        private var connection: SavedConnection? = null
        private var binding: CompanionBinding? = null
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class Transport : StreamingDirectProviderTransport {
        val requests = mutableListOf<JSONObject>()
        override suspend fun exchange(endpoint: String, key: String, json: String): String =
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", "OK")))).toString()
        override suspend fun exchangeStreaming(endpoint: String, key: String, json: String, onBytes: suspend (ByteArray) -> Unit) {
            requests.add(JSONObject(json))
            onBytes("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"看到了，慢慢说。\"}}]}\n\n".toByteArray())
            onBytes("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())
        }
    }
    private class NoEncoder : LocalSemanticEncoder {
        override val modelIdentity = "camera-gateway-no-model-v1"
        override val dimensions = 2
        override fun isAvailable() = false
        override fun documentChunkCount(text: String): Int = error("No model")
        override suspend fun encodeDocument(text: String): FloatArray = error("No model")
        override suspend fun encodeQuery(query: String): FloatArray = error("No model")
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("No model")
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("No model")
    }
    private suspend fun fixture(model: String = "deepseek-flash", nowElapsed: () -> Long = { 1_500L },
        test: suspend (LocalChatRepository, LocalChatDatabase, Transport, Conversation, NativeV4Context) -> Unit) {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            LocalChatDatabase(context, "call_camera_${UUID.randomUUID()}.sqlite3").use { database ->
                val transport = Transport()
                val repo = LocalChatRepository(context, Store(), DirectProviderClient(transport), database = database,
                    semanticEncoder = NoEncoder(), daily = null, runtimeElapsed = nowElapsed)
                repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-camera-key", model, CompanionProfile())
                test(repo, database, transport, repo.conversations().single(), context)
            }
        }
    }
}
