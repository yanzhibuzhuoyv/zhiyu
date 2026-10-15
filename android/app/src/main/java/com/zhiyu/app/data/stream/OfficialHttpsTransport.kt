package com.zhiyu.app.data.stream

import com.zhiyu.app.data.DirectProviderCatalog
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A permanently cancellable single POST, with a rendezvous queue for bounded public-text consumption. */
internal class OfficialHttpsTransport(private val calls: Call.Factory = officialClient) : StreamingDirectProviderTransport {
    override suspend fun exchange(endpoint: String, key: String, json: String): String {
        val output = ByteArrayOutputStream()
        response(endpoint, key, json, streaming = false).collect { output.write(it) }
        return try { LocalMemoryCodec.utf8(output.toByteArray()) }
        catch (_: java.nio.charset.CharacterCodingException) { throw invalidResponse() }
    }

    override suspend fun exchangeStreaming(endpoint: String, key: String, json: String,
        onBytes: suspend (ByteArray) -> Unit) {
        response(endpoint, key, json, streaming = true).collect { onBytes(it) }
    }

    private fun response(endpoint: String, key: String, json: String, streaming: Boolean) = callbackFlow {
        require(endpoint in DirectProviderCatalog.providers().map { DirectProviderCatalog.endpoint(it.id) }) { "AI 地址不受支持" }
        val active = AtomicReference<Response?>()
        val bytes = LocalMemoryCodec.bytes(json)
        val body = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true // Also blocks 503 Retry-After / HTTP2 follow-up replays.
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .header("Accept", if (streaming) "text/event-stream" else "application/json").post(body).build()
        val call = calls.newCall(request)
        suspendCancellableCoroutine<Unit> { lifetime ->
            // Register before enqueue. A cancel in the check/enqueue gap permanently cancels this same Call.
            channel.invokeOnClose {
                try { call.cancel() } finally {
                    try { active.getAndSet(null)?.close() }
                    finally { if (lifetime.isActive) lifetime.resumeWith(Result.success(Unit)) }
                }
            }
            lifetime.invokeOnCancellation { cause -> channel.close(cause) }
            if (!isActive || !lifetime.isActive) { call.cancel(); close(); return@suspendCancellableCoroutine }
            try { call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { close(failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    active.set(response)
                    try {
                        response.use {
                            if (!isActive) return
                            if (it.code != 200) throw upstreamFailure(it.code)
                            val responseBody = it.body
                            if (responseBody.contentLength() !in -1L..MAX_RESPONSE.toLong()) throw invalidResponse()
                            if (streaming && responseBody.contentType()?.let { type -> "${type.type}/${type.subtype}" } != "text/event-stream") {
                                throw invalidResponse()
                            }
                            responseBody.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                var size = 0
                                while (isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    size += count
                                    if (size > MAX_RESPONSE) throw invalidResponse()
                                    if (trySendBlocking(buffer.copyOf(count)).isFailure) break
                                }
                            }
                        }
                        close()
                    } catch (error: Exception) { close(failure(error)) }
                    finally { active.compareAndSet(response, null) }
                }
            }) } catch (error: Exception) { close(failure(error)) }
        }
    }.buffer(0)

    private fun failure(error: Exception): ApiFailure = when (error) {
        is ApiFailure -> error
        is SocketTimeoutException -> ApiFailure(504, "连接 AI 服务超时，请检查网络后重试", "provider_timeout")
        is java.nio.charset.CharacterCodingException, is IllegalArgumentException -> invalidResponse()
        else -> ApiFailure(0, "无法连接 AI 公司，请检查网络后重试", "provider_connection_failed")
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

    private fun invalidResponse() = ApiFailure(502, "AI 响应超过读取范围或格式无效", "provider_response_invalid")

    companion object {
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        internal val officialClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }
}
