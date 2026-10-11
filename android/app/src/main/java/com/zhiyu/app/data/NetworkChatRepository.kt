package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class NetworkChatRepository(store: CredentialStore, private val allowHttp: Boolean, private val api: HttpApi = HttpApi()) : ChatRepository {
    private val coordinator = SessionCoordinator(store, object : AuthService {
        override suspend fun authenticate(connection: SavedConnection): AuthSession =
            JSONObject(api.request(connection.serverUrl, "/api/auth/login", "POST", json = JSONObject()
                .put("provider_id", connection.providerId).put("api_key", connection.apiKey).put("model", connection.model))).auth()
    })
    override suspend fun savedConnection() = withContext(Dispatchers.IO) { coordinator.saved() }
    override suspend fun savedBinding() = withContext(Dispatchers.IO) {
        coordinator.saved()
        coordinator.binding()
    }
    override suspend fun providers(serverUrl: String): List<Provider> {
        val url = ConnectionRules.normalizeUrl(serverUrl, allowHttp)
        val health = JSONObject(api.request(url, "/health"))
        require(health.optString("status") == "ok") { "后端尚未就绪" }
        return JSONArray(api.request(url, "/api/providers")).mapObjects { it.provider() }
    }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) = withContext(Dispatchers.IO) {
        val url = ConnectionRules.normalizeUrl(serverUrl, allowHttp)
        val requested = CompanionRules.normalize(profile ?: coordinator.binding()?.profile)
        coordinator.login(url, providerId, apiKey, model, requested) { session ->
            try { prepareCompanion(url, session, requested).profile } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) {
                    // A failed profile admission must not leave a new local permanent choice.
                    try { api.request(url, "/api/auth/session", "DELETE", session.token) } catch (cleanup: Exception) {
                        if (cleanup is kotlinx.coroutines.CancellationException) throw cleanup
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }
    private suspend fun prepareCompanion(url: String, session: AuthSession, requested: CompanionProfile?): Conversation {
        val existing = JSONArray(api.request(url, "/api/conversations", token = session.token))
            .mapObjects { it.conversation() }.filter { it.model == session.model }
            .minWithOrNull(compareBy<Conversation> { it.createdAt }.thenBy { it.id })
        if (existing == null) {
            val json = JSONObject().put("title", "与你的日常").put("model", session.model).put("use_rag", true)
                .put("companion_profile", requested?.json() ?: JSONObject.NULL)
            return JSONObject(api.request(url, "/api/conversations", "POST", session.token, json)).conversation().also {
                check(it.profile == requested && it.model == session.model) { "服务暂未支持所选恋人设定，请检查服务版本" }
            }
        }
        if (requested == null || existing.profile == requested) return existing
        if (existing.profile?.mode == "preset") throw IllegalStateException("原 Key 的恋人预设已经固定，不能更换")
        if (requested.mode == "preset") throw IllegalStateException("这段已有聊天只能继续自行调教，不能改选固定预设")
        if (existing.profile?.mode == "custom") return existing
        return JSONObject(api.request(url, "/api/conversations/${existing.id}", "PATCH", session.token,
            JSONObject().put("companion_profile", requested.json()))).conversation()
    }
    override suspend fun logout() = withContext(Dispatchers.IO) {
        // Local keys must be removed even when the backend is offline.
        try {
            val saved = coordinator.saved()
            if (saved?.session != null) api.request(saved.serverUrl, "/api/auth/session", "DELETE", saved.session.token)
        } finally { coordinator.clear() }
        Unit
    }
    override suspend fun forgetLocalConnection() = withContext(Dispatchers.IO) { coordinator.clear() }
    private suspend fun request(path: String, method: String = "GET", json: JSONObject? = null): String = withContext(Dispatchers.IO) {
        coordinator.authorized { token ->
            val url = coordinator.saved()?.serverUrl ?: throw ApiFailure(401, "请先连接")
            api.request(url, path, method, token, json)
        }
    }
    override suspend fun conversations(): List<Conversation> {
        val conversations = JSONArray(request("/api/conversations")).mapObjects { it.conversation() }
        val binding = coordinator.binding()
        if (binding != null && !binding.profileConfigured) {
            val existing = conversations.filter { it.model == binding.model }
                .minWithOrNull(compareBy<Conversation> { it.createdAt }.thenBy { it.id })
            coordinator.adoptLegacyProfile(existing?.profile)
        }
        return conversations
    }
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?) = JSONObject(request("/api/conversations", "POST",
        JSONObject().put("title", "与你的日常").put("model", model).put("use_rag", useRag)
            .put("companion_profile", CompanionRules.normalize(profile)?.json() ?: JSONObject.NULL))).conversation()
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation {
        val normalized = CompanionRules.normalize(profile)
        CompanionRules.checkChange(coordinator.binding(), normalized)
        return JSONObject(request("/api/conversations/$id", "PATCH", JSONObject().put("companion_profile", normalized?.json() ?: JSONObject.NULL))).conversation().also {
            coordinator.updateTraining(it.profile)
        }
    }
    override suspend fun changeRag(id: String, enabled: Boolean) = JSONObject(request("/api/conversations/$id", "PATCH", JSONObject().put("use_rag", enabled))).conversation()
    override suspend fun deleteConversation(id: String) { request("/api/conversations/$id", "DELETE") }
    override suspend fun messages(id: String) = JSONArray(request("/api/conversations/$id/messages")).mapObjects { it.message() }
    override suspend fun history(id: String, before: String?): MessagePage {
        val cursor = before?.let { "&before=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
        val json = JSONObject(request("/api/conversations/$id/history?limit=100$cursor"))
        return MessagePage(json.getJSONArray("messages").mapObjects { it.message() }, json.getBoolean("has_more"),
            if (json.isNull("next_before")) null else json.getString("next_before"))
    }
    private fun memoryPath(id: String) = "/api/conversations/${encoded(id)}/memory"
    private fun encoded(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    override suspend fun memory(id: String, before: String?): MemoryPage {
        val cursor = before?.let { "&before=${encoded(it)}" }.orEmpty()
        return JSONObject(request("${memoryPath(id)}?limit=50$cursor")).memoryPage(before)
    }
    override suspend fun messageMemory(id: String, messageId: String) =
        JSONObject(request("${memoryPath(id)}/messages/${encoded(messageId)}")).memoryEntry()
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange) =
        JSONObject(request("${memoryPath(id)}/messages/${encoded(messageId)}", "PUT", change.json())).memoryEntry()
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> {
        val json = JSONObject(request("/api/conversations/$id/messages", "POST", JSONObject().put("content", ConnectionRules.message(content))))
        return json.getJSONObject("user_message").message() to json.getJSONObject("assistant_message").message()
    }
    override suspend fun documents() = JSONArray(request("/api/documents")).mapObjects { it.document() }
    override suspend fun upload(file: UploadedFile): Document = withContext(Dispatchers.IO) {
        val name = ConnectionRules.documentName(file.name, file.bytes.size)
        coordinator.authorized { token ->
            val url = coordinator.saved()?.serverUrl ?: throw ApiFailure(401, "请先连接")
            JSONObject(api.upload(url, "/api/documents", token, name, file.bytes)).document()
        }
    }
    override suspend fun deleteDocument(id: String) { request("/api/documents/$id", "DELETE") }
}
