package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Exercises real provider validation/JSON serialization, never a Key, network, or user archive. */
class PhotoSendFlowNativeTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext

    private class Store : CredentialStore {
        private var connection: SavedConnection? = null
        private var binding: CompanionBinding? = null
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }

    private class Transport : DirectProviderTransport {
        val chats = mutableListOf<JSONObject>()
        var failure: ApiFailure? = null
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            assertEquals("synthetic-key-only", key)
            val request = JSONObject(json)
            val messages = request.getJSONArray("messages")
            val chat = (0 until messages.length()).any {
                messages.getJSONObject(it).getString("role") == "system"
            }
            if (chat) {
                chats.add(request)
                started.complete(Unit)
                gate?.await()
                failure?.let { throw it }
            }
            return JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", "stop").put("message", JSONObject()
                    .put("role", "assistant").put("content", if (chat) "好呀，想听你说说这张图。" else "OK"))))
                .toString()
        }
    }

    private class UnavailableEncoder : LocalSemanticEncoder {
        override val modelIdentity = "photo-send-test-no-model-v1"
        override val dimensions = 2
        override fun isAvailable() = false
        override fun documentChunkCount(text: String): Int = error("No derived model work in this test")
        override suspend fun encodeDocument(text: String): FloatArray = error("No model request")
        override suspend fun encodeQuery(query: String): FloatArray = error("No model request")
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> =
            error("No model request")
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("No model request")
    }

    private fun png(): ByteArray = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).let { bitmap ->
        bitmap.eraseColor(0xff8aab86.toInt())
        try { ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
        } } finally { bitmap.recycle() }
    }

    private suspend fun repository(context: NativeV4Context, archive: LocalChatDatabase,
        transport: Transport, provider: String = "deepseek", model: String = "deepseek-flash"): LocalChatRepository {
        val repository = LocalChatRepository(context, Store(), DirectProviderClient(transport), database = archive,
            semanticEncoder = UnavailableEncoder(), daily = null)
        repository.login(LocalChatRepository.LOCAL_URL, provider, "synthetic-key-only", model, CompanionProfile())
        return repository
    }

    private fun messages(request: JSONObject): List<JSONObject> = request.getJSONArray("messages").let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }

    @Test fun deepseekPurePhotoWithoutImageKeyUsesRealTextProtocolAndKeepsExactOriginal(): Unit = runBlocking {
        NativeV4Context(base).use { context ->
            assertNull("Isolated image credentials must never come from a user's preferences", AgnesCredentialStore(context).read())
            LocalChatDatabase(context, "photo_send_${UUID.randomUUID()}.sqlite3").use { archive ->
                val transport = Transport(); val repo = repository(context, archive, transport)
                val chat = repo.conversations().single(); val source = png()
                val photo = ChatPhotoStore(context).importBytes(source)
                val pair = withTimeout(30_000) { repo.sendPhotoMessage(chat.id, "", photo.attachment) }
                val sent = messages(transport.chats.single())
                assertTrue("Non-vision models receive text protocol only", sent.all { it.get("content") is String })
                assertTrue(sent.any { it.getString("role") == "user" && it.getString("content").contains("用户附图") })
                assertTrue(sent.first().getString("content").contains("不要假装看见"))
                assertEquals("", pair.first.content)
                assertEquals(listOf(photo.attachment), pair.first.mediaAttachments)
                assertEquals(pair.first, repo.messages(chat.id).first())
                assertEquals("", archive.promptHistory("companion-" + LocalMemoryCodec.sha(
                    "deepseek\u0000deepseek-flash".toByteArray()), chat.id).first().content)
                assertArrayEquals(source, LocalMediaStore(context).download(photo.attachment.id, photo.attachment.sha256))
            }
        }
    }

    @Test fun qwenVisionPurePhotoSerializesOneImagePartThroughRealProviderClient(): Unit = runBlocking {
        NativeV4Context(base).use { context ->
            LocalChatDatabase(context, "photo_send_${UUID.randomUUID()}.sqlite3").use { archive ->
                val transport = Transport(); val repo = repository(context, archive, transport, "qwen", "qwen-vl-max")
                val chat = repo.conversations().single(); val photo = ChatPhotoStore(context).importBytes(png())
                val expected = ChatPhotoStore(context).payload(photo.attachment)
                val pair = repo.sendPhotoMessage(chat.id, "", photo.attachment)
                val user = messages(transport.chats.single()).last { it.getString("role") == "user" }
                val parts = user.getJSONArray("content")
                assertEquals(1, parts.length()); assertEquals("image_url", parts.getJSONObject(0).getString("type"))
                assertEquals(expected.dataUri(), parts.getJSONObject(0).getJSONObject("image_url").getString("url"))
                assertEquals("", pair.first.content); assertEquals(photo.attachment, pair.first.mediaAttachments.single())
                assertEquals("qwen-vl-max", transport.chats.single().getString("model"))
            }
        }
    }

    @Test fun failedReplyRetryReusesCommittedPhotoIdInsteadOfWritingAnotherUserOriginal(): Unit = runBlocking {
        NativeV4Context(base).use { context ->
            LocalChatDatabase(context, "photo_send_${UUID.randomUUID()}.sqlite3").use { archive ->
                val transport = Transport(); val repo = repository(context, archive, transport, "qwen", "qwen-vl-max")
                val chat = repo.conversations().single(); val photo = ChatPhotoStore(context).importBytes(png())
                transport.failure = ApiFailure(503, "synthetic reply unavailable", "provider_unavailable")
                try { repo.sendPhotoMessage(chat.id, "", photo.attachment); fail("The real provider failure must remain explicit") }
                catch (failure: ApiFailure) { assertEquals("provider_unavailable", failure.errorCode) }
                val committed = repo.messages(chat.id).single()
                assertEquals("user", committed.role); assertEquals(photo.attachment, committed.mediaAttachments.single())
                transport.failure = null
                val result = repo.sendPhotoMessage(chat.id, "", photo.attachment)
                val users = repo.messages(chat.id).filter { it.role == "user" }
                assertEquals("One intentional attachment ID is one original even after a failed reply", 1, users.size)
                assertEquals("A reply retry must preserve the original ID and timestamp", committed, result.first)
                assertEquals(1, repo.messages(chat.id).count { it.role == "assistant" })
                assertEquals(2, transport.chats.size)
            }
        }
    }

    @Test fun newSelectionOfSamePixelsIsAnotherIntentionalPhotoMessage(): Unit = runBlocking {
        NativeV4Context(base).use { context ->
            LocalChatDatabase(context, "photo_send_${UUID.randomUUID()}.sqlite3").use { archive ->
                val transport = Transport(); val repo = repository(context, archive, transport, "qwen", "qwen-vl-max")
                val chat = repo.conversations().single(); val bytes = png(); val photos = ChatPhotoStore(context)
                val first = photos.importBytes(bytes); val second = photos.importBytes(bytes)
                assertEquals(first.attachment.sha256, second.attachment.sha256)
                assertNotEquals(first.attachment.id, second.attachment.id)
                val sentFirst = repo.sendPhotoMessage(chat.id, "", first.attachment)
                val sentSecond = repo.sendPhotoMessage(chat.id, "", second.attachment)
                val users = repo.messages(chat.id).filter { it.role == "user" }
                assertEquals(listOf(sentFirst.first, sentSecond.first), users)
                assertEquals(listOf(first.attachment.id, second.attachment.id), users.map { it.mediaAttachments.single().id })
                assertEquals(2, transport.chats.size)
            }
        }
    }

    @Test fun committedPhotoIsReadableWhileRealProviderReplyIsStillPending(): Unit = runBlocking {
        NativeV4Context(base).use { context ->
            LocalChatDatabase(context, "photo_send_${UUID.randomUUID()}.sqlite3").use { archive ->
                val transport = Transport(); val repo = repository(context, archive, transport, "qwen", "qwen-vl-max")
                val chat = repo.conversations().single(); val photo = ChatPhotoStore(context).importBytes(png())
                transport.gate = CompletableDeferred()
                val sending = async { repo.sendPhotoMessage(chat.id, "", photo.attachment) }
                try {
                    withTimeout(30_000) { transport.started.await() }
                    assertFalse("Waiting for the answer must not pretend the entire turn has completed", sending.isCompleted)
                    val original = repo.messages(chat.id).single()
                    assertEquals("user", original.role); assertEquals("", original.content)
                    assertEquals(photo.attachment, original.mediaAttachments.single())
                    transport.gate!!.complete(Unit)
                    assertEquals(original, withTimeout(30_000) { sending.await() }.first)
                } finally { transport.gate?.complete(Unit); sending.cancel() }
            }
        }
    }
}
