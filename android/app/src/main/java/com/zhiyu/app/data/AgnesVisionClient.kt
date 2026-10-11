package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

fun interface ChatPhotoVision { suspend fun describe(photo: ChatPhotoPayload): String }
fun interface AgnesVisionTransport { suspend fun exchange(key: String, payload: String): String }

class AgnesVisionClient(
    private val credentials: () -> String?,
    private val transport: AgnesVisionTransport = AgnesVisionOfficialTransport(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatPhotoVision {
    override suspend fun describe(photo: ChatPhotoPayload): String = withTimeoutOrNull(180_000L) {
        val key = withContext(processingDispatcher) { credentials() }
            ?: throw ApiFailure(503, "请先在外观设置中配置图像服务 Key；也可发送带有清晰文字的图片", "photo_vision_unconfigured")
        AgnesImageProtocol.checkKey(key)
        val request = withContext(processingDispatcher) { AgnesVisionProtocol.payload(photo) }
        val response = transport.exchange(key, request)
        withContext(processingDispatcher) { AgnesVisionProtocol.parse(response) }
    } ?: throw ApiFailure(504, "图片识别等待超时，原图已保留", "photo_vision_timeout")
}
