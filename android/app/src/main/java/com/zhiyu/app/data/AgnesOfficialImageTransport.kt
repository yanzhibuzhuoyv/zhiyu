package com.zhiyu.app.data

import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed official TLS endpoint. A cancelled Call stays cancelled even before enqueue. */
internal class AgnesOfficialImageTransport(
    private val calls: Call.Factory = officialClient,
) : AgnesImageTransport {
    companion object {
        private val pending = Semaphore(4)
        internal val officialClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(360, TimeUnit.SECONDS)
            .callTimeout(360, TimeUnit.SECONDS).build()
    }

    override suspend fun exchange(key: String, payload: String): String = suspendCancellableCoroutine { continuation ->
        AgnesImageProtocol.checkKey(key)
        val bytes = LocalMemoryCodec.bytes(payload)
        require(bytes.size <= 8 * 1024 * 1024)
        if (!pending.tryAcquire()) {
            continuation.resumeWith(Result.failure(ApiFailure(503, "图片正在生成，请稍后再试", "image_service_busy")))
            return@suspendCancellableCoroutine
        }
        val finished = AtomicBoolean(false)
        fun finish(result: Result<String>) {
            if (finished.compareAndSet(false, true)) {
                pending.release()
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
        try {
            val request = Request.Builder().url(AgnesImageProtocol.ENDPOINT)
                .header("Authorization", "Bearer $key").header("Accept", "application/json")
                .post(bytes.toRequestBody("application/json".toMediaType())).build()
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finish(Result.failure(transportFailure(e)))
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) throw CancellationException()
                            if (it.code != 200) throw statusFailure(it.code)
                            val body = it.body
                            require(body.contentLength() in -1L..AgnesImageProtocol.MAX_RESPONSE_BYTES.toLong())
                            val value = body.byteStream().use { input ->
                                AgnesImageProtocol.readBounded(input) {
                                    if (!continuation.isActive) throw CancellationException()
                                }
                            }
                            Result.success(LocalMemoryCodec.utf8(value))
                        }
                    } catch (error: Exception) { Result.failure(transportFailure(error)) }
                    finish(result)
                }
            })
        } catch (error: Exception) { finish(Result.failure(transportFailure(error))) }
    }

    private fun transportFailure(error: Exception): Exception = when (error) {
        is CancellationException -> error
        is ApiFailure -> error
        is SocketTimeoutException -> ApiFailure(504, "图片生成等待超时，结果尚未确认", "image_request_unknown")
        is IllegalArgumentException, is java.nio.charset.CharacterCodingException -> AgnesImageProtocol.invalidResponse()
        else -> ApiFailure(0, "图像服务连接中断，结果尚未确认", "image_request_unknown")
    }

    private fun statusFailure(status: Int): ApiFailure = when (status) {
        401, 403 -> ApiFailure(status, "图像服务 Key 无效或没有调用权限", "image_auth_failed")
        402 -> ApiFailure(402, "图像服务余额不足", "image_balance_insufficient")
        429 -> ApiFailure(429, "图像服务限流或额度不足", "image_rate_limited")
        400, 404, 422 -> ApiFailure(status, "图像服务拒绝了本次图片请求", "image_request_invalid")
        in 300..399 -> ApiFailure(502, "图像服务返回重定向，已停止连接", "image_redirect_rejected")
        else -> ApiFailure(502, "图像服务暂时不可用，结果尚未确认", "image_request_unknown")
    }
}
