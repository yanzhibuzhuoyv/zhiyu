package com.zhiyu.app.data

import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

data class DirectChatMessage(val role: String, val content: String, val photos: List<ChatPhotoPayload> = emptyList())
fun interface DirectProviderTransport {
    suspend fun exchange(endpoint: String, key: String, json: String): String
}

/** A single official HTTPS request; no relay, redirect, backend session or private reasoning is displayed. */
open class DirectProviderClient(private val transport: DirectProviderTransport = OfficialHttpsTransport()) {
    open suspend fun validate(providerId: String, key: String, model: String) {
        val result = call(providerId, key, model, listOf(DirectChatMessage("user", "只回复 OK")), 8)
        val message = result.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        if (content(message).isBlank() && message.optString("reasoning_content").isBlank()) {
            throw ApiFailure(502, "AI 公司没有返回可用消息，无法验证模型", "provider_response_invalid")
        }
    }
    open suspend fun complete(providerId: String, key: String, model: String, messages: List<DirectChatMessage>, maxTokens: Int = 8192): String {
        val result = call(providerId, key, model, messages, maxTokens)
        val choice = result.getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") {
            throw ApiFailure(422, "模型输出预算已用完，未得到完整回答；请增加输出预算后重试", "provider_output_limit")
        }
        val message = choice.getJSONObject("message")
        val original = content(message)
        val answer = if (providerId == "minimax" && original.startsWith("<think>")) {
            val end = original.indexOf("</think>")
            if (end < 0) "" else original.substring(end + 8).trimStart()
        } else original
        if (answer.isBlank()) throw ApiFailure(502, if (message.optString("reasoning_content").isNotBlank())
            "模型仍只有思考过程，没有最终回答；请增加输出预算或换用已授权非思考模型" else "模型没有返回文本回答，请重试", "provider_response_invalid")
        checkedText(answer)
        return answer
    }
    private suspend fun call(providerId: String, key: String, model: String, messages: List<DirectChatMessage>, maxTokens: Int): JSONObject {
        val endpoint = DirectProviderCatalog.endpoint(providerId)
        require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 }) { "请完整填写 API Key，不能含空格或控制字符" }
        require(model.isNotBlank() && model.length <= 256 && model.none { it.isISOControl() }) { "请填写有效的已开通模型 ID" }
        require(maxTokens in 1..65536) { "输出预算应为1至65536 token" }
        require(messages.size in 1..100 && messages.sumOf { it.content.length.toLong() } <= 256000) { "本次聊天上下文超过范围" }
        require(messages.all { it.role in setOf("system", "user", "assistant") &&
            (it.content.isNotBlank() || it.photos.isNotEmpty()) && it.content.length <= 128000 &&
            it.photos.size <= 1 && (it.photos.isEmpty() || it.role == "user") }) { "聊天上下文格式不正确" }
        require(messages.sumOf { it.photos.sumOf(ChatPhotoPayload::byteCount).toLong() } <= 5L * 1024 * 1024) { "本次图片上下文过大" }
        require(messages.all { it.photos.isEmpty() } || ChatPhotoRules.nativeVision(providerId, model)) { "此模型未验证图片能力" }
        messages.forEach { LocalMemoryCodec.bytes(it.content) }
        if (providerId == "kimi" && (model == "kimi-k3" || model.startsWith("kimi-k2.7-code"))) {
            throw IllegalArgumentException("该 Kimi 模型需保留思考消息，本版暂未适配；请使用已开通的 K2.6")
        }
        val json = JSONObject().put("model", model).put("messages", JSONArray().also { array ->
            messages.forEach { message ->
                val content: Any = if (message.photos.isEmpty()) message.content else JSONArray().also { parts ->
                    if (message.content.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", message.content))
                    message.photos.forEach { parts.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", it.dataUri()).apply {
                            if (providerId == "deepseek") put("detail", "high")
                        })) }
                }
                array.put(JSONObject().put("role", message.role).put("content", content))
            }
        }).put("stream", false).put("max_tokens", maxTokens)
        applyModelOptions(json, providerId, model)
        val response = transport.exchange(endpoint, key, json.toString())
        try {
            require(LocalMemoryCodec.bytes(response).size <= 2 * 1024 * 1024)
            val result = JSONObject(response)
            require(!result.has("error") && !result.has("error_code"))
            require(result.getJSONArray("choices").length() > 0)
            result.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return result
        } catch (_: org.json.JSONException) {
            throw ApiFailure(502, "AI 公司返回的响应格式不正确", "provider_response_invalid")
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(502, "AI 公司未能完成本次请求，请检查模型与配额", "provider_response_invalid")
        }
    }
    private fun applyModelOptions(json: JSONObject, provider: String, model: String) {
        val disable = (provider == "deepseek" && model in setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro")) ||
            (provider == "kimi" && model == "kimi-k2.6") || (provider == "zhipu" && model in setOf("glm-4.5-flash", "glm-5.2")) ||
            (provider == "doubao" && (model.startsWith("doubao-seed-") || model.startsWith("ep-"))) || (provider == "tokenhub" && model == "hy3")
        if (disable) json.put("thinking", JSONObject().put("type", "disabled"))
        if ((provider == "qwen" && model in setOf("qwen-plus", "qwen-flash")) ||
            (provider == "siliconflow" && model == "deepseek-ai/DeepSeek-V4-Flash")) json.put("enable_thinking", false)
    }
    private fun content(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> checkedText(content)
        is JSONArray -> (0 until content.length()).joinToString("") { index ->
            val part = content.optJSONObject(index) ?: throw ApiFailure(502, "AI 返回了不支持的消息格式", "provider_response_invalid")
            if (part.optString("type") != "text" || part.opt("text") !is String) throw ApiFailure(502, "AI 未返回纯文本消息", "provider_response_invalid")
            checkedText(part.getString("text"))
        }
        else -> ""
    }
    private fun checkedText(text: String): String {
        try { LocalMemoryCodec.bytes(text) }
        catch (_: IllegalArgumentException) { throw ApiFailure(502, "AI 返回了无效的 Unicode 文本，未改写或保存原文", "provider_response_invalid") }
        return text
    }
}

private class OfficialHttpsTransport : DirectProviderTransport {
    companion object {
        private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "zhiyu-official-ai").apply { isDaemon = true } }
        private const val MAX_RESPONSE = 2 * 1024 * 1024
    }
    override suspend fun exchange(endpoint: String, key: String, json: String): String = suspendCancellableCoroutine { continuation ->
        require(endpoint in DirectProviderCatalog.providers().map { DirectProviderCatalog.endpoint(it.id) }) { "AI 地址不受支持" }
        val activeConnection = AtomicReference<HttpsURLConnection?>()
        val task = executor.submit {
            var connection: HttpsURLConnection? = null
            try {
                if (!continuation.isActive) return@submit
                connection = URL(endpoint).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
                activeConnection.set(connection)
                if (!continuation.isActive) return@submit
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 0 // A complete answer may be long; cancellation disconnects the socket.
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                val bytes = json.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                if (status != 200) throw upstreamFailure(status)
                require(connection.contentLengthLong <= MAX_RESPONSE) { "AI 响应超过安全读取范围" }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (continuation.isActive) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_RESPONSE) { "AI 响应超过安全读取范围" }
                        output.write(buffer, 0, count)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(Result.success(LocalMemoryCodec.utf8(output.toByteArray())))
            } catch (error: Exception) {
                val failure = when (error) {
                    is ApiFailure -> error
                    is SocketTimeoutException -> ApiFailure(504, "连接 AI 服务超时，请检查网络后重试", "provider_timeout")
                    is java.nio.charset.CharacterCodingException -> ApiFailure(502, "AI 返回了无效的 UTF-8 响应", "provider_response_invalid")
                    is IllegalArgumentException -> ApiFailure(502, "AI 响应超过安全读取范围", "provider_response_invalid")
                    else -> ApiFailure(0, "无法连接 AI 公司，请检查网络后重试", "provider_connection_failed")
                }
                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
            } finally { connection?.disconnect(); activeConnection.set(null) }
        }
        continuation.invokeOnCancellation { activeConnection.get()?.disconnect(); task.cancel(true) }
    }
    private fun upstreamFailure(status: Int): ApiFailure = when (status) {
        401 -> ApiFailure(401, "API Key 无效或已失效，请确认 AI 公司与密钥对应", "provider_auth_failed")
        403 -> ApiFailure(403, "该 Key 没有模型调用权限，请检查模型、地域及密钥类型", "provider_auth_failed")
        402 -> ApiFailure(402, "AI 服务余额不足，请检查对应平台余额", "provider_balance_insufficient")
        429 -> ApiFailure(429, "AI 服务限流或额度不足，请稍后重试", "provider_rate_limited")
        400, 404, 422 -> ApiFailure(400, "AI 公司拒绝请求，请检查模型 ID、接入点与上下文长度", "provider_request_invalid")
        408, 504 -> ApiFailure(504, "AI 服务响应超时，请稍后重试", "provider_timeout")
        in 300..399 -> ApiFailure(502, "AI 公司返回了重定向，已停止连接，请稍后重试", "provider_redirect_rejected")
        else -> ApiFailure(502, "AI 服务暂时不可用，请稍后重试", "provider_unavailable")
    }
}
