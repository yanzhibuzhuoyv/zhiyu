package com.zhiyu.app.domain

data class Provider(
    val id: String, val name: String, val defaultModel: String,
    val models: List<String>, val modelHint: String, val docUrl: String,
)
data class Conversation(
    val id: String, val title: String, val model: String, val useRag: Boolean,
    val createdAt: String, val updatedAt: String, val profile: CompanionProfile? = null,
)
data class Source(val documentId: String, val name: String, val text: String, val score: Double)
data class Message(val id: String, val role: String, val content: String, val sources: List<Source>, val createdAt: String,
    val mediaAttachments: List<MediaAttachment> = emptyList(), val origin: String = "reply")
data class MessagePage(val messages: List<Message>, val hasMore: Boolean, val nextBefore: String?)
data class Document(val id: String, val name: String, val size: Long, val chunks: Int, val createdAt: String)
data class UploadedFile(val name: String, val bytes: ByteArray)
data class ChatImageDraft(val attachment: MediaAttachment)
data class CompanionProfile(
    val mode: String = "preset", val style: String = "gentle", val role: String = "partner",
    val name: String = "", val userName: String = "", val instructions: String = "",
)
data class CompanionBinding(val providerId: String, val model: String, val profile: CompanionProfile? = null, val profileConfigured: Boolean = true)
data class AuthSession(val token: String, val providerId: String, val model: String, val expiresAt: Long) {
    override fun toString() = "AuthSession(providerId=$providerId, model=$model, expiresAt=$expiresAt)"
}
data class SavedConnection(
    val serverUrl: String, val providerId: String, val apiKey: String,
    val model: String, val session: AuthSession? = null,
) {
    override fun toString() = "SavedConnection(serverUrl=$serverUrl, providerId=$providerId, model=$model)"
}
class ApiFailure(val status: Int, message: String, val errorCode: String? = null) : Exception(message) {
    // Unclassified 401 responses retain compatibility with older backend sessions.
    val isSessionExpired: Boolean get() = status == 401 && (errorCode == null || errorCode == "session_expired")
}

interface CredentialStore {
    fun read(): SavedConnection?
    fun write(connection: SavedConnection)
    fun clear()
    fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection): Boolean = synchronized(this) {
        if (read() != expected) false else { write(updated); true }
    }
    fun readBinding(): CompanionBinding?
    fun writeBinding(binding: CompanionBinding)
}
interface AuthService { suspend fun authenticate(connection: SavedConnection): AuthSession }
interface ChatRepository {
    suspend fun connectionNotice(): String? = null
    suspend fun savedBinding(): CompanionBinding?
    suspend fun savedConnection(): SavedConnection?
    suspend fun providers(serverUrl: String): List<Provider>
    suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile? = null)
    suspend fun logout()
    suspend fun forgetLocalConnection()
    suspend fun conversations(): List<Conversation>
    suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile? = null): Conversation
    suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation
    suspend fun changeRag(id: String, enabled: Boolean): Conversation
    suspend fun deleteConversation(id: String)
    suspend fun messages(id: String): List<Message>
    suspend fun history(id: String, before: String? = null): MessagePage
    suspend fun memory(id: String, before: String? = null): MemoryPage
    suspend fun messageMemory(id: String, messageId: String): MemoryEntry
    suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry
    suspend fun sendMessage(id: String, content: String): Pair<Message, Message>
    suspend fun sendMessage(id: String, content: String, onSubmitted: (Message) -> Unit): Pair<Message, Message> =
        sendMessage(id, content).also { onSubmitted(it.first) }
    suspend fun sendVoiceMessage(id: String, content: String, voiceDraftId: String,
        onSubmitted: (Message) -> Unit): Pair<Message, Message> =
        throw IllegalStateException("当前连接暂不支持语音消息")
    suspend fun discardVoiceDraft(id: String): Unit = Unit
    suspend fun sendQueuedMessage(id: String, content: String, requestId: String,
        image: MediaAttachment? = null, voiceDraftId: String? = null,
        onSubmitted: (Message) -> Unit = {}): Pair<Message, Message> = when {
        voiceDraftId != null -> sendVoiceMessage(id, content, voiceDraftId, onSubmitted)
        image != null -> sendPhotoMessage(id, content, image, onSubmitted)
        else -> sendMessage(id, content, onSubmitted)
    }
    /** Commit originals atomically and notify in input order; a batch has exactly one shared answer. */
    suspend fun sendQueuedTextBatch(id: String, inputs: List<QueuedTextInput>,
        onSubmitted: (String, Message) -> Unit = { _, _ -> }): QueuedTextBatchResult {
        val checked = QueuedTextBatchRules.check(inputs)
        check(checked.size == 1) { "当前连接暂不支持连续文字统一回复，请逐条发送" }
        val input = checked.single()
        val pair = sendQueuedMessage(id, input.content, input.requestId, onSubmitted = { onSubmitted(input.requestId, it) })
        return QueuedTextBatchResult(listOf(QueuedTextSubmission(input.requestId, pair.first)), pair.second)
    }
    /** Persist only the display order of a continuous turn; never rewrite raw message contents or their RAG order. */
    suspend fun recordReplyTurn(id: String, userIds: List<String>, assistantIds: List<String>): Unit = Unit
    suspend fun sendCallMessage(id: String, content: String, onDelta: suspend (String) -> Unit): Pair<Message, Message> =
        sendMessage(id, content).also { onDelta(it.second.content) }
    suspend fun sendCallVisualMessage(id: String, content: String, frame: CallCameraFrame,
        onDelta: suspend (String) -> Unit): Pair<Message, Message> =
        throw IllegalStateException("当前连接暂不支持查看通话画面")
    suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment): Pair<Message, Message> =
        throw IllegalStateException("当前连接不支持发送图片")
    suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment,
        onSubmitted: (Message) -> Unit): Pair<Message, Message> =
        sendPhotoMessage(id, content, attachment).also { onSubmitted(it.first) }
    suspend fun savedPhotoDraft(): ChatImageDraft? = null
    suspend fun discardPhotoDraft(id: String) = Unit
    suspend fun documents(): List<Document>
    suspend fun upload(file: UploadedFile): Document
    suspend fun deleteDocument(id: String)
}
