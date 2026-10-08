package com.zhiyu.app.data

import android.content.Context
import android.net.Uri
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class VisualRepository(store: CredentialStore, private val allowHttp: Boolean, private val api: HttpApi = HttpApi()) {
    private val coordinator = SessionCoordinator(store, object : AuthService {
        override suspend fun authenticate(connection: SavedConnection): AuthSession =
            JSONObject(api.request(ConnectionRules.normalizeUrl(connection.serverUrl, allowHttp), "/api/auth/login", "POST",
                json = JSONObject().put("provider_id", connection.providerId).put("api_key", connection.apiKey).put("model", connection.model))).auth()
    })
    private fun url() = ConnectionRules.normalizeUrl(coordinator.saved()?.serverUrl ?: throw ApiFailure(401, "请先连接"), allowHttp)
    private fun path(id: String): String {
        require(Regex("[a-f0-9]{32}").matches(id)) { "恋人会话编号无效" }
        return "/api/conversations/$id/visual"
    }
    private suspend fun request(path: String, method: String = "GET", json: JSONObject? = null) =
        coordinator.authorized { token -> JSONObject(api.request(url(), path, method, token, json)) }
    suspend fun profile(id: String) = request(path(id)).visualProfile()
    suspend fun templates() = coordinator.authorized { token ->
        JSONArray(api.request(url(), "/api/media/templates", token = token)).mapObjects { it.visualTemplate() }
    }
    suspend fun useTemplate(id: String, templateId: String): VisualProfile {
        MediaRules.templatePath(templateId)
        return request(path(id) + "/template", "POST", JSONObject().put("template_id", templateId)).visualProfile()
    }
    suspend fun update(id: String, mode: String, description: String, gender: String, hair: String) = request(path(id), "PUT",
        JSONObject().put("mode", mode).put("description", description).put("gender", gender).put("hair", hair)).visualProfile()
    suspend fun generate(id: String, requestId: String, description: String) = request(path(id) + "/generate", "POST",
        JSONObject().put("request_id", requestId).put("description", description)).visualProfile()
    suspend fun import(id: String, bytes: ByteArray) = coordinator.authorized { token ->
        JSONObject(api.upload(url(), path(id) + "/reference", token, "oc-reference", bytes)).visualProfile()
    }
    suspend fun download(id: String, expectedSha: String? = null, template: Boolean = false): ByteArray = coordinator.authorized { token ->
        api.download(url(), if (template) MediaRules.templatePath(id) else MediaRules.path(id), token).also { bytes ->
            if (expectedSha != null) require(MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) } == expectedSha) { "图片校验失败，请刷新聊天" }
        }
    }
}

class VisualReferenceReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择本机图片" }
        val input = resolver.openInputStream(uri) ?: throw IllegalArgumentException("无法打开参考图片")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MediaRules.MAX_BYTES) { "OC 参考图不能超过 5 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()) { "参考图为空" } }
        }
    }
}
