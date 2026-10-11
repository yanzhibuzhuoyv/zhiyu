package com.zhiyu.app.data

import android.graphics.BitmapFactory
import com.zhiyu.app.domain.AgnesGeneratedImage
import com.zhiyu.app.domain.AgnesImageProtocol
import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface AgnesImageTransport {
    suspend fun exchange(key: String, payload: String): String
}

fun interface AgnesImageBoundsValidator {
    fun validate(bytes: ByteArray, mimeType: String)
}

/** Only returns validated bytes. Storage, OC confirmation and generation quotas belong to callers. */
class AgnesImageClient(
    private val transport: AgnesImageTransport = AgnesOfficialImageTransport(),
    private val bounds: AgnesImageBoundsValidator = AndroidAgnesImageBounds,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun generate(key: String, prompt: String, reference: ByteArray? = null,
        size: String = "1K", ratio: String = "3:4"): AgnesGeneratedImage = withTimeoutOrNull(360_000L) {
        val payload = withContext(processingDispatcher) {
            AgnesImageProtocol.checkKey(key)
            require(reference == null || reference.size in 1..AgnesImageProtocol.MAX_IMAGE_BYTES) {
                "图片超过 5 MiB 或内容为空"
            }
            val copy = reference?.copyOf()
            copy?.let { bounds.validate(it, AgnesImageProtocol.imageMime(it)) }
            AgnesImageProtocol.payload(prompt, copy, size, ratio)
        }
        val response = transport.exchange(key, payload)
        withContext(processingDispatcher) {
            val generated = AgnesImageProtocol.parse(response)
            try { bounds.validate(generated.bytes, generated.mimeType) }
            catch (_: IllegalArgumentException) { throw AgnesImageProtocol.invalidResponse() }
            generated
        }
    } ?: throw ApiFailure(504, "图片生成等待超时，结果尚未确认", "image_request_unknown")
}

internal object AndroidAgnesImageBounds : AgnesImageBoundsValidator {
    override fun validate(bytes: ByteArray, mimeType: String) {
        require(AgnesImageProtocol.imageMime(bytes) == mimeType) { "图片类型不匹配" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outMimeType == mimeType && options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
            options.outWidth.toLong() * options.outHeight <= 20_000_000L) { "图片无法解码或超过 2000 万像素" }
    }
}
