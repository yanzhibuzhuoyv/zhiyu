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
    override suspend fun providers(serverUrl: String): List<Provider> {
        val url = ConnectionRules.normalizeUrl(serverUrl, allowHttp)
        val health = JSONObject(api.request(url, "/health"))
        require(health.optString("status") == "ok") { "后端尚未就绪" }
        return JSONArray(api.request(url, "/api/providers")).mapObjects { it.provider() }
    }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String) = withContext(Dispatchers.IO) {
        coordinator.login(ConnectionRules.normalizeUrl(serverUrl, allowHttp), providerId, apiKey, model)
    }
    override suspend fun logout() = withContext(Dispatchers.IO) {
        val saved = coordinator.saved()
        // Local keys must be removed even when the backend is offline.
        try {
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
    override suspend fun conversations() = JSONArray(request("/api/conversations")).mapObjects { it.conversation() }
    override suspend fun createConversation(model: String, useRag: Boolean) = JSONObject(request("/api/conversations", "POST",
        JSONObject().put("title", "新对话").put("model", model).put("use_rag", useRag))).conversation()
    override suspend fun changeRag(id: String, enabled: Boolean) = JSONObject(request("/api/conversations/$id", "PATCH", JSONObject().put("use_rag", enabled))).conversation()
    override suspend fun deleteConversation(id: String) { request("/api/conversations/$id", "DELETE") }
    override suspend fun messages(id: String) = JSONArray(request("/api/conversations/$id/messages")).mapObjects { it.message() }
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
