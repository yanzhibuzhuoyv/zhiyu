package com.zhiyu.app.domain

data class MediaAttachment(val id: String, val kind: String, val url: String, val mimeType: String,
    val sha256: String, val dayId: String?, val visualVersion: Int, val sourceLabel: String)
data class GenerationQuota(val configured: Boolean, val used: Int, val pending: Int, val remaining: Int, val limit: Int = 2)
data class VisualProfile(val mode: String, val description: String, val gender: String, val hair: String,
    val visualVersion: Int, val referenceId: String?, val referenceUrl: String?, val generation: GenerationQuota)
data class VisualTemplate(val id: String, val name: String, val description: String, val gender: String, val hair: String,
    val referenceUrl: String, val referenceSha256: String, val imageCount: Int)

object MediaRules {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val identifier = Regex("[a-f0-9]{32}")
    fun path(id: String): String {
        require(identifier.matches(id)) { "素材编号无效" }
        return "/api/media/assets/$id"
    }
    fun templatePath(id: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(id)) { "外观样例编号无效" }
        return "/api/media/templates/$id/reference"
    }
    fun check(value: MediaAttachment): MediaAttachment {
        require(value.url == path(value.id)) { "素材地址不属于当前服务" }
        require(value.kind in setOf("image", "sticker") && value.mimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "素材格式无效" }
        require(Regex("[a-f0-9]{64}").matches(value.sha256) && value.visualVersion >= 0) { "素材元信息无效" }
        return value
    }
}
