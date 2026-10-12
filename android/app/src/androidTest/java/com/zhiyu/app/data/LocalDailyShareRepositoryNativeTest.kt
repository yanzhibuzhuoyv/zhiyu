package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

/** Real repository/SQLite and real provider JSON carrying image pixels; responses are synthetic. */
class LocalDailyShareRepositoryNativeTest {
    private val profile = CompanionProfile(mode = "custom", name = "测试恋人", instructions = "说话简短，喜欢分享日常")

    @Test fun sendsOneRequiredActualImageAndPreservesCaptionAsAssistantOriginal() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image()
            val sent = repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture)
            assertEquals("assistant", sent.role); assertEquals("daily_share", sent.origin)
            assertEquals(client.reply, sent.content)
            assertEquals(listOf(picture), sent.mediaAttachments)
            assertEquals(listOf(sent), repo.messages(f.chat))
            val request = client.requests.single()
            assertEquals("deepseek-flash", request.getString("model"))
            val messages = request.getJSONArray("messages")
            val photos = (0 until messages.length()).map { messages.getJSONObject(it) }
                .filter { it.opt("content") is JSONArray }.flatMap { item ->
                    val parts = item.getJSONArray("content")
                    (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.getString("type") == "image_url" }
                }
            assertEquals(1, photos.size)
            val imageUrl = photos.single().getJSONObject("image_url").getString("url")
            assertArrayEquals(ChatPhotoImageProcessor().normalize(f.assets.download(picture.id, picture.sha256)).bytes,
                Base64.getDecoder().decode(imageUrl.substringAfter(',')))
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains(profile.instructions)); assertTrue(system.contains(f.slot.activity))
            assertTrue(system.contains("虚构")); assertFalse(system.contains("ZHIYU_MEDIA_"))
        }
    }

    @Test fun replayAfterExpiryReturnsOriginalWithoutASecondPaidCallOrAnotherUserMessage() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image()
            val first = repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture)
            f.now = f.event.expires
            val other = repository(f, client, checkNotNull(repo.savedConnection()), checkNotNull(repo.savedBinding()))
            try {
                assertEquals(first, other.first.sendDailyShare(f.chat, f.event.requestId, f.event, picture))
                assertEquals(1, client.requests.size)
                assertEquals(listOf(first), repo.messages(f.chat))
            } finally { other.second.close() }
        }
    }

    @Test fun expiredOrWrongRequestOrMissingImageFailsBeforeProviderAndNeverWritesChat() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image()
            assertTrue(failure { repo.sendDailyShare(f.chat, UUID.randomUUID().toString(), f.event, picture) } is IllegalArgumentException)
            f.now = f.event.expires
            assertTrue(failure { repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture) } is IllegalArgumentException)
            assertTrue(client.requests.isEmpty()); assertTrue(repo.messages(f.chat).isEmpty())
        }
        withRepository { f, repo, client, _ ->
            val sticker = f.image(kind = "sticker")
            assertTrue(failure { repo.sendDailyShare(f.chat, f.event.requestId, f.event, sticker) } is IllegalArgumentException)
            assertTrue(client.requests.isEmpty()); assertTrue(repo.messages(f.chat).isEmpty())
        }
    }

    @Test fun keyOrPersonalityChangedDuringCaptionGenerationDiscardsLateResponse() = runBlocking {
        for (change in listOf("key", "personality")) withRepository { f, repo, client, state ->
            val picture = f.image(); client.gate = CompletableDeferred()
            val task = async { failure { repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture) } }
            withTimeout(10_000) { client.started.await() }
            if (change == "key") state.saved = state.saved.copy(apiKey = "changed-synthetic-key")
            else state.binding = state.binding.copy(profile = profile.copy(instructions = "已经更改的设定"))
            client.gate!!.complete(Unit)
            assertTrue(task.await() is IllegalStateException)
            assertTrue(repo.messages(f.chat).isEmpty())
        }
    }

    @Test fun visualOrExpiryOrPixelChangesDuringGenerationNeverCommitAStaleCaption() = runBlocking {
        for (change in listOf("visual", "expiry", "pixels", "trajectory")) withRepository { f, repo, client, _ ->
            val picture = f.image(); client.gate = CompletableDeferred()
            val task = async { failure { repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture) } }
            withTimeout(10_000) { client.started.await() }
            when (change) {
                "visual" -> f.visual.update(f.chat, "import", "changed", "female", "long")
                "expiry" -> f.now = f.event.expires
                "trajectory" -> f.changePersistedActivity()
                else -> f.corruptBlob(picture)
            }
            client.gate!!.complete(Unit)
            assertTrue(task.await() is IllegalArgumentException)
            assertTrue(repo.messages(f.chat).isEmpty())
        }
    }

    @Test fun aSecondRequestCannotShareTheSameDecodedPhotoAgain() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image()
            repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture)
            val another = f.event.copy(requestId = UUID.randomUUID().toString())
            assertTrue(failure { repo.sendDailyShare(f.chat, another.requestId, another, picture) } is IllegalArgumentException)
            assertEquals(1, client.requests.size); assertEquals(1, repo.messages(f.chat).size)
        }
    }

    @Test fun failedOrCancelledCaptionDoesNotInsertAUserOrAssistantOrConsumeImage() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image(); client.failure = ApiFailure(503, "synthetic failure")
            assertTrue(failure { repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture) } is ApiFailure)
            assertTrue(repo.messages(f.chat).isEmpty())
            assertNull(repo.proactiveResult(f.chat, f.event.requestId))
            assertArrayEquals(f.png(), f.assets.download(picture.id, picture.sha256))
        }
        withRepository { f, repo, client, _ ->
            val picture = f.image(); client.gate = CompletableDeferred()
            val task = launch { repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture) }
            withTimeout(10_000) { client.started.await() }; task.cancelAndJoin()
            assertTrue(repo.messages(f.chat).isEmpty())
            assertNull(repo.proactiveResult(f.chat, f.event.requestId))
        }
    }

    @Test fun generatedCalendarOrMediaCommandsNeverExecuteOrReplaceTheRequiredPicture() = runBlocking {
        withRepository { f, repo, client, _ ->
            val picture = f.image()
            client.reply = "分享一点今天的小事。\n[[ZHIYU_MEDIA_bad:other]]\n[[ZHIYU_CALENDAR_bad:{\"title\":\"不要写入\"}]]"
            val result = repo.sendDailyShare(f.chat, f.event.requestId, f.event, picture)
            assertEquals("分享一点今天的小事。", result.content)
            assertEquals(listOf(picture), result.mediaAttachments)
            assertNull(repo.lastCalendarNotice)
        }
    }

    private suspend fun withRepository(test: suspend (DailyShareNativeFixture, LocalChatRepository, Transport, Store) -> Unit) {
        ProactiveForeground.setVisible(false)
        val client = Transport()
        val saved = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-key-only", "deepseek-flash")
        val binding = CompanionBinding("deepseek", "deepseek-flash", profile)
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        val initial = DailyShareNativeFixture()
        try {
            val db = LocalChatDatabase(initial.context, "daily_share_test.sqlite3")
            try {
                val chat = db.ensureConversation(owner, saved.model, profile)
                DailyShareNativeFixture(chat.id).use { f ->
                    val (repo, archive, state) = repository(f, client, saved, binding)
                    try { test(f, repo, client, state) } finally { archive.close() }
                }
            } finally { db.close() }
        } finally { initial.close(); ProactiveForeground.setVisible(false) }
    }

    private fun repository(f: DailyShareNativeFixture, client: Transport, saved: SavedConnection,
        binding: CompanionBinding): Triple<LocalChatRepository, LocalChatDatabase, Store> {
        val archive = LocalChatDatabase(f.context, "daily_share_repository.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("${binding.providerId}\u0000${binding.model}".toByteArray())
        val db = archive.writableDatabase
        // Fixture uses the real repository's ownership and schema but retains its selected chat ID.
        archive.ensureConversation(owner, saved.model, binding.profile).also { created ->
            if (created.id != f.chat) db.execSQL("UPDATE conversations SET id=? WHERE id=?", arrayOf(f.chat, created.id))
        }
        val state = Store(saved, binding)
        return Triple(LocalChatRepository(f.context, state, DirectProviderClient(client), database = archive,
            daily = f.daily, dailyNow = { f.now }), archive, state)
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected failure")
    }

    private class Store(var saved: SavedConnection, var binding: CompanionBinding) : CredentialStore {
        override fun read() = saved
        override fun write(connection: SavedConnection) { saved = connection }
        override fun clear() = error("no real credentials")
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }

    private class Transport : DirectProviderTransport {
        val requests = mutableListOf<JSONObject>()
        var reply = "今天的小事，也想分一点给你。"
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            assertEquals("synthetic-key-only", key)
            requests.add(JSONObject(json)); started.complete(Unit); gate?.await(); failure?.let { throw it }
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", reply)))).toString()
        }
    }
}
