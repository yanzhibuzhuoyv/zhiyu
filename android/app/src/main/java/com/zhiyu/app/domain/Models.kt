package com.zhiyu.app.domain

data class Provider(
    val id: String, val name: String, val defaultModel: String,
    val models: List<String>, val modelHint: String, val docUrl: String,
)
data class Conversation(
    val id: String, val title: String, val model: String, val useRag: Boolean,
    val createdAt: String, val updatedAt: String,
)
data class Source(val documentId: String, val name: String, val text: String, val score: Double)
data class Message(val id: String, val role: String, val content: String, val sources: List<Source>, val createdAt: String)
data class Document(val id: String, val name: String, val size: Long, val chunks: Int, val createdAt: String)
data class UploadedFile(val name: String, val bytes: ByteArray)
data class AuthSession(val token: String, val providerId: String, val model: String, val expiresAt: Long) {
    override fun toString() = "AuthSession(providerId=$providerId, model=$model, expiresAt=$expiresAt)"
}
data class SavedConnection(
    val serverUrl: String, val providerId: String, val apiKey: String,
    val model: String, val session: AuthSession? = null,
) {
    override fun toString() = "SavedConnection(serverUrl=$serverUrl, providerId=$providerId, model=$model)"
}
class ApiFailure(val status: Int, message: String) : Exception(message)

interface CredentialStore {
    fun read(): SavedConnection?
    fun write(connection: SavedConnection)
    fun clear()
}
interface AuthService { suspend fun authenticate(connection: SavedConnection): AuthSession }
interface ChatRepository {
    suspend fun savedConnection(): SavedConnection?
    suspend fun providers(serverUrl: String): List<Provider>
    suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String)
    suspend fun logout()
    suspend fun forgetLocalConnection()
    suspend fun conversations(): List<Conversation>
    suspend fun createConversation(model: String, useRag: Boolean): Conversation
    suspend fun changeRag(id: String, enabled: Boolean): Conversation
    suspend fun deleteConversation(id: String)
    suspend fun messages(id: String): List<Message>
    suspend fun sendMessage(id: String, content: String): Pair<Message, Message>
    suspend fun documents(): List<Document>
    suspend fun upload(file: UploadedFile): Document
    suspend fun deleteDocument(id: String)
}
