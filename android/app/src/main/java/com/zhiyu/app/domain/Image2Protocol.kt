package com.zhiyu.app.domain

import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.Collections

class Image2Request internal constructor(val prompt: String, val model: String, reference: ByteArray?) {
    private val original = reference?.copyOf()
    val reference: ByteArray? get() = original?.copyOf()
    val endpoint: String get() = Image2Protocol.API_ORIGIN + if (original == null) "/v1/images/generations" else "/v1/images/edits"
    val fields: Map<String, String> get() = mapOf("model" to model, "prompt" to prompt, "n" to "1",
        "size" to "1024x1024", "quality" to "medium", "output_format" to "png")
    val json: String get() = "{\"model\":${AgnesImageJson.quote(model)},\"prompt\":${AgnesImageJson.quote(prompt)}," +
        "\"n\":1,\"size\":\"1024x1024\",\"quality\":\"medium\",\"output_format\":\"png\"}"
    override fun toString() = "Image2Request(model=$model, referenceBytes=${original?.size ?: 0})"
}

sealed interface Image2Output {
    class Inline(val image: AgnesGeneratedImage) : Image2Output
    class Url(val value: String) : Image2Output { override fun toString() = "Image2Output.Url" }
}

/** Observed OOAPI schema; images remain candidates until the user confirms their appearance. */
object Image2Protocol {
    const val API_ORIGIN = "https://cn-api.ooapi.cc"
    const val DEFAULT_MODEL = "gpt-image-2.5-flare"
    val MODELS: Set<String> = Collections.unmodifiableSet(setOf("gpt-image-2", "gpt-image-2.5-flare", "gpt-image-2.5-sunburst"))
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 4_000_000L
    private const val MAX_BASE64 = ((MAX_IMAGE_BYTES + 2) / 3) * 4
    private val pngMagic = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun checkKey(key: String) {
        require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 }) {
            "请完整填写图像服务 API Key，不能含空格或控制字符"
        }
    }

    fun request(prompt: String, reference: ByteArray? = null, model: String = DEFAULT_MODEL): Image2Request {
        require(model in MODELS) { "图像模型不受支持，请重新选择" }
        require(prompt.isNotBlank() && prompt.length <= 4000) { "图片描述应为 1 至 4000 字符" }
        LocalMemoryCodec.bytes(prompt)
        reference?.let { require(imageMime(it) == "image/png") { "角色参考图须为 PNG 图片" } }
        return Image2Request(prompt, model, reference)
    }

    fun imageMime(bytes: ByteArray): String {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "图片超过 5 MiB 或内容为空" }
        return when {
            bytes.size >= 8 && pngMagic.indices.all { bytes[it] == pngMagic[it] } -> "image/png"
            bytes.size >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() -> "image/jpeg"
            else -> throw IllegalArgumentException("图像服务只接受 PNG 或 JPEG 图片")
        }
    }

    /** Generic strict JSON parser is shared; the request and response schemas are Image2's own. */
    fun parse(response: String): Image2Output = try {
        require(response.length <= MAX_RESPONSE_BYTES && LocalMemoryCodec.bytes(response).size <= MAX_RESPONSE_BYTES)
        val root = AgnesImageJson.parse(response) as? Map<*, *> ?: throw IllegalArgumentException()
        require(root["error"] == null && root["errors"] == null && root["code"] == null)
        val rows = root["data"] as? List<*> ?: throw IllegalArgumentException()
        require(rows.size == 1)
        val row = rows.single() as? Map<*, *> ?: throw IllegalArgumentException()
        if (row.containsKey("b64_json")) {
            val encoded = row["b64_json"] as? String ?: throw IllegalArgumentException()
            require(encoded.length in 1..MAX_BASE64)
            val bytes = Base64.getDecoder().decode(encoded)
            require(Base64.getEncoder().encodeToString(bytes) == encoded)
            Image2Output.Inline(AgnesGeneratedImage(bytes, imageMime(bytes)))
        } else {
            val url = row["url"] as? String ?: throw IllegalArgumentException()
            Image2Output.Url(checkDownloadUrl(url))
        }
    } catch (_: IllegalArgumentException) { throw invalidResponse() }

    /** Only recognized machine codes influence public messages. Never relay a provider message. */
    fun statusFailure(status: Int, body: String? = null): ApiFailure {
        val code = errorCode(body)
        if (status == 402 || code in setOf("INSUFFICIENT_BALANCE", "insufficient_balance", "insufficient_quota"))
            return ApiFailure(status, "图像服务余额或额度不足，请充值后重试", "image_balance_insufficient")
        return when (status) {
            401, 403 -> ApiFailure(status, "图像服务 Key 无效或没有调用权限", "image_auth_failed")
            429 -> ApiFailure(status, "图像服务正在限流，请稍后再试", "image_rate_limited")
            400, 404, 422 -> ApiFailure(status, "图像服务拒绝了本次图片请求", "image_request_invalid")
            in 300..399 -> ApiFailure(502, "图像服务返回重定向，已停止连接", "image_redirect_rejected")
            else -> ApiFailure(status, "图像服务连接中断，结果尚未确认", "image_request_unknown")
        }
    }

    private fun errorCode(body: String?): String? = try {
        if (body == null || body.length > 65_536) null else {
            val root = AgnesImageJson.parse(body) as? Map<*, *>
            val nested = root?.get("error") as? Map<*, *> ?: root?.get("errors") as? Map<*, *>
            (root?.get("code") ?: nested?.get("code") ?: nested?.get("type")) as? String
        }
    } catch (_: IllegalArgumentException) { null }

    fun checkDownloadUrl(value: String): String {
        require(value.length in 1..4096 && value.none { it.code <= 32 || it.code == 127 }) { "图片下载地址无效" }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("图片下载地址无效") }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("图片下载地址无效")
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443)) {
            "图片下载仅支持不带认证的标准 HTTPS 地址"
        }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal")) {
            "图片下载不支持本地地址"
        }
        if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
            val address = try { InetAddress.getByName(host.removeSurrounding("[", "]")) }
                catch (_: Exception) { throw IllegalArgumentException("图片下载地址无效") }
            require(isPublicAddress(address)) { "图片下载不支持私网地址" }
        } else require(host.contains('.') && !host.endsWith('.') &&
            host.split('.').all { it.isNotEmpty() && it.length <= 63 && it.first() != '-' && it.last() != '-' &&
                it.all { char -> char in 'a'..'z' || char in '0'..'9' || char == '-' } }) { "图片下载地址无效" }
        return value
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) return publicIpv4(bytes)
        if (bytes.size != 16) return false
        // IPv6 global unicast only; reject Teredo, 6to4 and documentation ranges.
        if (bytes[0] and 0xe0 != 0x20 || bytes[0] == 0x20 && bytes[1] == 2) return false
        if (bytes.take(4) == listOf(0x20, 1, 0, 0) || bytes.take(4) == listOf(0x20, 1, 0x0d, 0xb8)) return false
        return true
    }

    private fun publicIpv4(b: List<Int>): Boolean = when {
        b[0] in setOf(0, 10, 127) || b[0] >= 224 -> false
        b[0] == 100 && b[1] in 64..127 -> false
        b[0] == 169 && b[1] == 254 -> false
        b[0] == 172 && b[1] in 16..31 -> false
        b[0] == 192 && (b[1] == 168 || b[1] == 0 && b[2] in setOf(0, 2)) -> false
        b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) -> false
        b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
        else -> true
    }

    fun invalidResponse() = ApiFailure(502, "图像服务未返回有效图片，原图已保留", "image_response_invalid")
}
