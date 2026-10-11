package com.zhiyu.app.data

import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed TLS endpoint, no redirects or retries; a Key cannot be forwarded to an image URL. */
class AgnesVisionOfficialTransport(private val calls: Call.Factory = AgnesOfficialImageTransport.officialClient) : AgnesVisionTransport {
    companion object { private val pending = Semaphore(4) }
    override suspend fun exchange(key: String, payload: String): String = suspendCancellableCoroutine { continuation ->
        AgnesImageProtocol.checkKey(key)
        val bytes = LocalMemoryCodec.bytes(payload)
        require(bytes.size <= 8 * 1024 * 1024) { "图片请求过大" }
        if (!pending.tryAcquire()) {
            continuation.resumeWith(Result.failure(ApiFailure(503, "图片正在识别，请稍后再试", "photo_vision_busy")))
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
            val request = Request.Builder().url(AgnesVisionProtocol.ENDPOINT).header("Authorization", "Bearer $key")
                .header("Accept", "application/json").post(bytes.toRequestBody("application/json".toMediaType())).build()
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finish(Result.failure(failure(e)))
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) throw CancellationException()
                            if (it.code != 200) throw status(it.code)
                            require(it.body.contentLength() in -1L..AgnesVisionProtocol.MAX_RESPONSE_BYTES.toLong())
                            val content = it.body.byteStream().use { input ->
                                AgnesImageProtocol.readBounded(input, AgnesVisionProtocol.MAX_RESPONSE_BYTES) {
                                    if (!continuation.isActive) throw CancellationException()
                                }
                            }
                            Result.success(LocalMemoryCodec.utf8(content))
                        }
                    } catch (error: Exception) { Result.failure(failure(error)) }
                    finish(result)
                }
            })
        } catch (error: Exception) { finish(Result.failure(failure(error))) }
    }
    private fun failure(error: Exception): Exception = when (error) {
        is ApiFailure, is CancellationException -> error
        is SocketTimeoutException -> ApiFailure(504, "图片识别等待超时，原图已保留", "photo_vision_timeout")
        is IllegalArgumentException, is java.nio.charset.CharacterCodingException -> ApiFailure(502, "图片识别响应无效，原图已保留", "photo_vision_invalid")
        else -> ApiFailure(0, "图像识别暂时无法连接，原图已保留", "photo_vision_connection_failed")
    }
    private fun status(value: Int) = when (value) {
        401, 403 -> ApiFailure(value, "图像服务 Key 无效或没有调用权限", "photo_vision_auth_failed")
        402 -> ApiFailure(value, "图像服务余额不足", "photo_vision_balance_insufficient")
        429 -> ApiFailure(value, "图像服务限流，请稍后再试", "photo_vision_rate_limited")
        in 300..399 -> ApiFailure(502, "图像服务返回重定向，已停止连接", "photo_vision_redirect_rejected")
        else -> ApiFailure(502, "图片暂时没看清，原图已保留", "photo_vision_failed")
    }
}
