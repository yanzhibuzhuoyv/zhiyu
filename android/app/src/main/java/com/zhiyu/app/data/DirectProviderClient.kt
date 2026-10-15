package com.zhiyu.app.data

import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ChatPhotoPayload
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.data.stream.DeepSeekSseDecoder
import com.zhiyu.app.data.stream.StreamingDirectProviderTransport
import com.zhiyu.app.data.stream.OfficialHttpsTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class DirectChatMessage(val role: String, val content: String, val photos: List<ChatPhotoPayload> = emptyList())
fun interface DirectProviderTransport {
    suspend fun exchange(endpoint: String, key: String, json: String): String
}

/** A single official HTTPS request; no relay, redirect, backend session or private reasoning is displayed. */
open class DirectProviderClient(private val transport: DirectProviderTransport = OfficialHttpsTransport()) {
    /** Emits provisional public answer text; only the returned complete answer may be committed. */
    open suspend fun completeStreaming(providerId: String, key: String, model: String,
        messages: List<DirectChatMessage>, maxTokens: Int = 8192, onDelta: suspend (String) -> Unit): String {
        currentCoroutineContext().ensureActive()
        // Existing subclasses commonly override complete only. Preserve that dispatch without accidental networking.
        if (providerId != "deepseek" || transport !is StreamingDirectProviderTransport || javaClass != DirectProviderClient::class.java) {
            val answer = complete(providerId, key, model, messages, maxTokens)
            currentCoroutineContext().ensureActive()
            onDelta(answer)
            currentCoroutineContext().ensureActive()
            return answer
        }
        val request = request(providerId, key, model, messages, maxTokens, stream = true)
        val decoder = DeepSeekSseDecoder(onDelta)
        val completed = StreamCompleted()
        try {
            transport.exchangeStreaming(request.first, key, request.second) { bytes ->
                decoder.accept(bytes)
                if (decoder.isDone) throw completed // Do not wait for server EOF after protocol completion.
            }
        } catch (signal: StreamCompleted) { if (signal !== completed) throw signal }
        currentCoroutineContext().ensureActive()
        return checkedText(decoder.finish())
    }
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
        val request = request(providerId, key, model, messages, maxTokens, stream = false)
        val response = transport.exchange(request.first, key, request.second)
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
    private fun request(providerId: String, key: String, model: String, messages: List<DirectChatMessage>, maxTokens: Int,
        stream: Boolean): Pair<String, String> {
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
        }).put("stream", stream).put("max_tokens", maxTokens)
        applyModelOptions(json, providerId, model)
        return endpoint to json.toString()
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
    private class StreamCompleted : RuntimeException(null, null, false, false)
}
