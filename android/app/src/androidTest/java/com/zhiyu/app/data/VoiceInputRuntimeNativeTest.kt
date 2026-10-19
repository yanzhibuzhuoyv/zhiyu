package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.voicemessages.VoiceClipState
import com.zhiyu.app.data.voicemessages.VoiceMessageStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real queued send, WAV ledger, SQLite and serialized provider body; only HTTPS is substituted. */
class VoiceInputRuntimeNativeTest {
    @Test fun recordedVoiceSuppliesAsrProvenanceWithoutRewritingItsTranscriptOrClaimingAudioUpload(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val transcript = "我想和你一起去次火锅，别发语音，打字就好。"
            val draft = f.voices.recordDraft(FloatArray(1600) { .25f }, 16000, transcript)
            assertEquals(VoiceClipState.READY, draft.state)
            val originalAudio = f.voices.read(draft.id).samples
            val result = f.repo.sendQueuedMessage(chat.id, transcript, "1".repeat(32), null, draft.id) { }
            val system = f.system()
            assertTrue(system.contains("input_modality=asr_transcript"))
            assertTrue(system.contains("original_audio_available=true"))
            assertTrue(system.contains("audio_supplied_to_model=false"))
            assertTrue(system.contains("高置信的同音"))
            assertTrue("Output format must not determine the user's input provenance", system.contains("voice_reply_planned=false"))
            assertEquals(listOf(transcript), f.users().map { promptOriginal(it.getString("content")) })
            assertEquals("voice_user:${draft.id}", result.first.origin)
            assertEquals(transcript, result.first.content)
            val raw = checkNotNull(f.db.recognitionOriginal(f.owner, chat.id, result.first.id))
            assertEquals(transcript, raw.text)
            assertEquals(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(transcript)), raw.sha256)
            assertEquals(draft.sourceSha, f.voices.find(draft.id)?.sourceSha)
            assertEquals(result.first.id, f.voices.find(draft.id)?.messageId)
            assertArrayEquals(originalAudio, f.voices.read(draft.id).samples, 0f)
            assertEquals(1, f.chatCalls)
        }
    }

    @Test fun laterTypedTextAboutVoiceAndTyposCannotInheritThePreviousTurnsAsrStatus(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.open()
            val first = "我想去次火锅，别发语音。"
            val draft = f.voices.recordDraft(FloatArray(1600) { .1f }, 16000, first)
            f.repo.sendQueuedMessage(chat.id, first, "2".repeat(32), null, draft.id) { }
            val typed = "我打字聊一下语音转写和错别字，这次不是录音。别发语音，打字就好。"
            val result = f.repo.sendQueuedMessage(chat.id, typed, "3".repeat(32), null, null) { }
            val system = f.system()
            assertTrue(system.contains("input_modality=text"))
            assertTrue(system.contains("original_audio_available=false"))
            assertTrue(system.contains("audio_supplied_to_model=false"))
            assertFalse(system.contains("input_modality=asr_transcript"))
            assertFalse(system.contains("高置信的同音"))
            assertEquals(listOf(first, typed), f.users().map { promptOriginal(it.getString("content")) })
            assertEquals("reply", result.first.origin)
            assertEquals(listOf(first, typed), f.db.history(f.owner, chat.id).messages.filter { it.role == "user" }.map { it.content })
            assertEquals(2, f.chatCalls)
        }
    }

    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        val db = LocalChatDatabase(context, "voice_input_${UUID.randomUUID()}.sqlite3")
        val voices = VoiceMessageStore(context)
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        var chatCalls = 0
        private var request: JSONObject? = null
        private val store = object : CredentialStore {
            private var saved: SavedConnection? = null
            private var companion: CompanionBinding? = null
            override fun read() = saved
            override fun write(connection: SavedConnection) { saved = connection }
            override fun clear() { saved = null }
            override fun readBinding() = companion
            override fun writeBinding(binding: CompanionBinding) { companion = binding }
        }
        val repo = LocalChatRepository(context, store, DirectProviderClient { _, key, body ->
            assertEquals("synthetic-voice-input-only", key)
            val parsed = JSONObject(body)
            val rows = parsed.getJSONArray("messages")
            if ((0 until rows.length()).any { rows.getJSONObject(it).getString("role") == "system" }) {
                request = parsed; chatCalls++
            }
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"好呀，想吃哪一锅？"}}]}"""
        }, database = db, semanticEncoder = object : LocalSemanticEncoder {
            override val modelIdentity = "voice-input-no-encoder"
            override val dimensions = 2
            override fun isAvailable() = false
            override fun documentChunkCount(text: String): Int = error("Unused")
            override suspend fun encodeDocument(text: String): FloatArray = error("Unused")
            override suspend fun encodeQuery(query: String): FloatArray = error("Unused")
            override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("Unused")
            override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("Unused")
        }, daily = null)
        suspend fun open(): Conversation {
            repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-voice-input-only", "deepseek-flash", CompanionProfile())
            return repo.conversations().single()
        }
        private fun rows(): List<JSONObject> {
            val rows = checkNotNull(request).getJSONArray("messages")
            return (0 until rows.length()).map(rows::getJSONObject)
        }
        fun system(): String = rows().single { it.getString("role") == "system" }.getString("content")
        fun users(): List<JSONObject> = rows().filter { it.getString("role") == "user" }
        override fun close() {
            db.close(); context.close()
            val folder = context.filesDir.canonicalFile
            check(folder.parentFile == base.filesDir.canonicalFile && folder.name.startsWith("v4_"))
            check(folder.deleteRecursively())
        }
    }
}
