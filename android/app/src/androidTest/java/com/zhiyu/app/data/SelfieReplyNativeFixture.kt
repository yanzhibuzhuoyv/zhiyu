package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import java.util.UUID

/** Real local archive and media selection, with a provider that fails on any unwanted request. */
internal class SelfieReplyNativeFixture(context: Context, profile: CompanionProfile = CompanionProfile()) : AutoCloseable {
    val database = LocalChatDatabase(context, "selfie_caption_${UUID.randomUUID()}.sqlite3")
    val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
    var calls = 0
    var failIfCalled = true
    val answer = "考试没发挥好先别急，今晚先休息。这张给你看看。"
    private val store = object : CredentialStore {
        private var connection: SavedConnection? = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-selfie-caption", "deepseek-flash")
        private var bound: CompanionBinding? = CompanionBinding("deepseek", "deepseek-flash", profile)
        override fun read() = connection
        override fun write(connection: SavedConnection) { this.connection = connection }
        override fun clear() { connection = null }
        override fun readBinding() = bound
        override fun writeBinding(binding: CompanionBinding) { bound = binding }
    }
    private val encoder = object : LocalSemanticEncoder {
        override val modelIdentity = "selfie-caption-no-encoder"
        override val dimensions = 2
        override fun isAvailable() = false
        override fun documentChunkCount(text: String): Int = error("unused")
        override suspend fun encodeDocument(text: String): FloatArray = error("unused")
        override suspend fun encodeQuery(query: String): FloatArray = error("unused")
        override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("unused")
        override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("unused")
    }
    val repo = LocalChatRepository(context, store, DirectProviderClient { _, _, _ ->
        calls++
        check(!failIfCalled) { "A ready standalone selfie must not ask the chat model to deny its own attachment" }
        """{"choices":[{"finish_reason":"stop","message":{"content":"$answer"}}]}"""
    }, database = database, semanticEncoder = encoder, daily = null)
    suspend fun open() = repo.conversations().single()
    override fun close() = database.close()
}
