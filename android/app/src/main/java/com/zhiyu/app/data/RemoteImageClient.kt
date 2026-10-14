package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zhiyu.app.domain.AgnesGeneratedImage
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.Image2Protocol
import com.zhiyu.app.domain.Image2Request
import com.zhiyu.app.domain.Image2Output
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface RemoteImageTransport { suspend fun exchange(key: String, request: Image2Request): String }
fun interface RemoteImageDownload { suspend fun fetch(url: String): ByteArray }
fun interface RemoteImageBoundsValidator { fun validate(bytes: ByteArray, mimeType: String) }

class RemoteImageClient(
    private val transport: RemoteImageTransport = HttpsRemoteImageTransport(),
    private val download: RemoteImageDownload = HttpsRemoteImageTransport(),
    private val bounds: RemoteImageBoundsValidator = AndroidRemoteImageBounds,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val model: String = Image2Protocol.DEFAULT_MODEL,
) {
    init { require(model in Image2Protocol.MODELS) { "图像模型不受支持，请重新选择" } }

    suspend fun generate(key: String, prompt: String, reference: ByteArray? = null): AgnesGeneratedImage {
        Image2Protocol.checkKey(key)
        require(reference == null || reference.size in 1..Image2Protocol.MAX_IMAGE_BYTES) { "角色参考图超过 5 MiB 或为空" }
        val snapshot = reference?.copyOf()
        return withTimeoutOrNull(180_000L) {
            val request = withContext(processingDispatcher) {
                val prepared = Image2Protocol.request(prompt, snapshot, model)
                snapshot?.let { bounds.validate(it, "image/png") }
                prepared
            }
            val response = transport.exchange(key, request)
            val result = withContext(processingDispatcher) { Image2Protocol.parse(response) }
            val image = when (result) {
                is Image2Output.Inline -> result.image
                is Image2Output.Url -> {
                    rejectReflectedKey(result.value, key)
                    val bytes = download.fetch(result.value)
                    try { AgnesGeneratedImage(bytes, Image2Protocol.imageMime(bytes)) }
                    catch (_: IllegalArgumentException) { throw Image2Protocol.invalidResponse() }
                }
            }
            withContext(processingDispatcher) {
                try { bounds.validate(image.bytes, image.mimeType) }
                catch (_: IllegalArgumentException) { throw Image2Protocol.invalidResponse() }
                image
            }
        } ?: throw ApiFailure(504, "图片生成等待超时，结果尚未确认；请先检查记录再决定重试", "image_request_unknown")
    }

    private fun rejectReflectedKey(url: String, key: String) {
        var decoded = url
        repeat(3) {
            if (decoded.contains(key)) throw Image2Protocol.invalidResponse()
            decoded = try { URLDecoder.decode(decoded.replace("+", "%2B"), "UTF-8") }
                catch (_: IllegalArgumentException) { throw Image2Protocol.invalidResponse() }
        }
        if (decoded.contains(key)) throw Image2Protocol.invalidResponse()
    }
}

internal object AndroidRemoteImageBounds : RemoteImageBoundsValidator {
    override fun validate(bytes: ByteArray, mimeType: String) {
        require(Image2Protocol.imageMime(bytes) == mimeType) { "图片类型不匹配" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType == mimeType && options.outWidth in 1..4096 && options.outHeight in 1..4096 &&
            options.outWidth.toLong() * options.outHeight <= Image2Protocol.MAX_IMAGE_PIXELS) { "图片无法解码或超过 400 万像素" }
        // Bounds alone can accept a header whose pixel stream is corrupt or absent.
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })
        require(decoded != null) { "图片内容无法解码" }
        try { require(decoded.width == options.outWidth && decoded.height == options.outHeight) { "图片尺寸不一致" } }
        finally { decoded.recycle() }
    }
}

internal class PublicImageDns(private val resolve: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = resolve(hostname)
        if (addresses.isEmpty() || addresses.any { !Image2Protocol.isPublicAddress(it) })
            throw UnknownHostException("图片下载地址不在公网")
        return addresses
    }
}

/** Fixed authenticated origin. Downloads use the same safe client but no credentials or cookies. */
internal class HttpsRemoteImageTransport(private val calls: Call.Factory = officialClient) : RemoteImageTransport, RemoteImageDownload {
    companion object {
        private val pending = Semaphore(4)
        internal val officialClient: OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
            .proxy(Proxy.NO_PROXY).dns(PublicImageDns())
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).build()
    }

    override suspend fun exchange(key: String, request: Image2Request): String {
        Image2Protocol.checkKey(key)
        // Revalidate callers that construct a request inside this module rather than through the client.
        val checked = Image2Protocol.request(request.prompt, request.reference, request.model)
        val reference = checked.reference
        val body = if (reference != null) {
            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                checked.fields.forEach { (name, value) -> addFormDataPart(name, value) }
                addFormDataPart("image[]", "reference.png", reference.toRequestBody("image/png".toMediaType()))
            }.build()
        } else LocalMemoryCodec.bytes(checked.json).toRequestBody("application/json".toMediaType())
        val http = Request.Builder().url(checked.endpoint).header("Authorization", "Bearer $key")
            .header("Accept", "application/json").post(body).build()
        return LocalMemoryCodec.utf8(execute(http, Image2Protocol.MAX_RESPONSE_BYTES))
    }

    override suspend fun fetch(url: String): ByteArray {
        val safe = Image2Protocol.checkDownloadUrl(url)
        val request = Request.Builder().url(safe).header("Accept", "image/png,image/jpeg").get().build()
        return execute(request, Image2Protocol.MAX_IMAGE_BYTES)
    }

    private suspend fun execute(request: Request, limit: Int): ByteArray = suspendCancellableCoroutine { continuation ->
        if (!pending.tryAcquire()) {
            continuation.resumeWith(Result.failure(ApiFailure(503, "图片正在生成，请稍后再试", "image_service_busy")))
            return@suspendCancellableCoroutine
        }
        val finished = AtomicBoolean(false)
        fun finish(result: Result<ByteArray>) {
            if (finished.compareAndSet(false, true)) {
                pending.release()
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
        try {
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) { finish(Result.failure(CancellationException())); return@suspendCancellableCoroutine }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) = finish(Result.failure(transportFailure(error)))
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!continuation.isActive) throw CancellationException()
                            val body = it.body
                            if (it.code != 200) {
                                val details = if (it.code in setOf(400, 401, 402, 403, 404, 422, 429)) {
                                    try { LocalMemoryCodec.utf8(readBounded(body.byteStream(), 65_536) {
                                        if (!continuation.isActive) throw CancellationException()
                                    }) } catch (_: IllegalArgumentException) { null }
                                } else null
                                throw Image2Protocol.statusFailure(it.code, details)
                            }
                            require(body.contentLength() in -1L..limit.toLong())
                            Result.success(readBounded(body.byteStream(), limit) {
                                if (!continuation.isActive) throw CancellationException()
                            })
                        }
                    } catch (failure: Exception) { Result.failure(transportFailure(failure)) }
                    finish(result)
                }
            })
        } catch (failure: Exception) { finish(Result.failure(transportFailure(failure))) }
    }

    private fun readBounded(input: InputStream, limit: Int, checkActive: () -> Unit): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && output.size().toLong() + count <= limit) { "图像响应过长或无效" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun transportFailure(failure: Exception): Exception = when (failure) {
        is CancellationException -> failure
        is ApiFailure -> failure
        is SocketTimeoutException -> ApiFailure(504, "图片生成等待超时，结果尚未确认；请先检查记录再决定重试", "image_request_unknown")
        is IllegalArgumentException, is java.nio.charset.CharacterCodingException -> Image2Protocol.invalidResponse()
        else -> ApiFailure(0, "图像服务连接中断，结果尚未确认", "image_request_unknown")
    }
}
