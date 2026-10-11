package com.zhiyu.app.domain

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/** Image bytes are immutable to callers; generated pictures are candidates, not identity proofs. */
class AgnesGeneratedImage(bytes: ByteArray, val mimeType: String) {
    private val original = bytes.copyOf()
    val bytes: ByteArray get() = original.copyOf()
    val byteCount: Int get() = original.size
    override fun toString() = "AgnesGeneratedImage(mimeType=$mimeType, byteCount=$byteCount)"
}

/** Pure contract for the documented official endpoint. No credentials enter JSON or error bodies. */
object AgnesImageProtocol {
    const val ENDPOINT = "https://apihub.agnes-ai.com/v1/images/generations"
    const val MODEL = "agnes-image-2.5-flash"
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val MAX_BASE64_CHARS = ((MAX_IMAGE_BYTES + 2) / 3) * 4
    private val sizes = setOf("1K", "2K", "3K", "4K")
    private val ratios = setOf("1:1", "3:4", "4:3", "16:9", "9:16", "2:3", "3:2", "21:9")
    private val pngMagic = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun checkKey(key: String) {
        require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 }) {
            "请完整填写图像服务 API Key，不能含空格或控制字符"
        }
    }

    fun payload(prompt: String, reference: ByteArray? = null, size: String = "1K", ratio: String = "3:4"): String {
        require(prompt.isNotBlank() && prompt.length <= 4000) { "图片描述应为 1 至 4000 字符" }
        LocalMemoryCodec.bytes(prompt)
        require(size in sizes && ratio in ratios) { "图片尺寸或比例不受支持" }
        val referencePart = reference?.let {
            val mime = imageMime(it)
            ",\"image\":[${AgnesImageJson.quote("data:$mime;base64," + Base64.getEncoder().encodeToString(it))}]"
        } ?: ""
        return "{\"model\":${AgnesImageJson.quote(MODEL)},\"prompt\":${AgnesImageJson.quote(prompt)}," +
            "\"size\":${AgnesImageJson.quote(size)},\"ratio\":${AgnesImageJson.quote(ratio)}," +
            "\"return_base64\":true,\"extra_body\":{\"response_format\":\"b64_json\"$referencePart}}"
    }

    fun parse(response: String): AgnesGeneratedImage = try {
        require(response.length <= MAX_RESPONSE_BYTES && LocalMemoryCodec.bytes(response).size <= MAX_RESPONSE_BYTES)
        val root = AgnesImageJson.parse(response) as? Map<*, *> ?: throw IllegalArgumentException()
        require(root["error"] == null && root["error_code"] == null)
        val rows = root["data"] as? List<*> ?: throw IllegalArgumentException()
        require(rows.size == 1)
        val row = rows.single() as? Map<*, *> ?: throw IllegalArgumentException()
        // Agnes can return a URL alongside Base64. Ignore it completely; only inline bytes are used.
        val encoded = row["b64_json"] as? String ?: throw IllegalArgumentException()
        require(encoded.length in 1..MAX_BASE64_CHARS)
        val bytes = Base64.getDecoder().decode(encoded)
        AgnesGeneratedImage(bytes, imageMime(bytes))
    } catch (_: IllegalArgumentException) { throw invalidResponse() }

    fun imageMime(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "图片超过 5 MiB 或内容为空" }
        return when {
            bytes.size >= pngMagic.size && pngMagic.indices.all { bytes[it] == pngMagic[it] } -> "image/png"
            bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() -> "image/jpeg"
            else -> throw IllegalArgumentException("图像服务只接受 PNG 或 JPEG 图片")
        }
    }

    fun readBounded(input: InputStream, limit: Int = MAX_RESPONSE_BYTES, checkActive: () -> Unit = {}): ByteArray {
        require(limit in 1..MAX_RESPONSE_BYTES)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                require(output.size() < limit) { "图像服务响应过长" }
                output.write(byte)
            } else {
                require(output.size().toLong() + count <= limit) { "图像服务响应过长" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun invalidResponse() = ApiFailure(502, "图像服务未返回有效图片，原图已保留", "image_response_invalid")
}
