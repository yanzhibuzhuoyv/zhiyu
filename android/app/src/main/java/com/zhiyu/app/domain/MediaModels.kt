package com.zhiyu.app.domain

data class MediaAttachment(val id: String, val kind: String, val url: String, val mimeType: String,
    val sha256: String, val dayId: String?, val visualVersion: Int, val sourceLabel: String)
data class GenerationQuota(val configured: Boolean, val used: Int, val pending: Int, val remaining: Int, val limit: Int = 2)
data class VisualProfile(val mode: String, val description: String, val gender: String, val hair: String,
    val visualVersion: Int, val referenceId: String?, val referenceUrl: String?, val generation: GenerationQuota)
data class VisualTemplate(val id: String, val name: String, val description: String, val gender: String, val hair: String,
    val referenceUrl: String, val referenceSha256: String, val imageCount: Int)
data class PortraitPreview(val id: String, val originalId: String, val subjectId: String,
    val originalUrl: String, val subjectUrl: String, val originalSha256: String, val subjectSha256: String,
    val originalWidth: Int, val originalHeight: Int, val bounds: List<Int>, val expectedVisualVersion: Int,
    val method: String, val needsConfirmation: Boolean, val coordinateSpace: String = "original_pixels",
    val sourceWidth: Int = originalWidth, val sourceHeight: Int = originalHeight)

object PortraitRules {
    private val digest = Regex("[a-f0-9]{64}")
    fun check(value: PortraitPreview): PortraitPreview {
        MediaRules.path(value.id)
        require(value.originalUrl == MediaRules.path(value.originalId) && value.subjectUrl == MediaRules.path(value.subjectId)) { "人物预览地址无效" }
        require(digest.matches(value.originalSha256) && digest.matches(value.subjectSha256)) { "人物预览校验信息无效" }
        require(value.originalWidth in 1..8192 && value.originalHeight in 1..8192 &&
            value.originalWidth.toLong() * value.originalHeight <= 20_000_000) { "人物预览尺寸无效" }
        PhotoInputRules.checkSource(value.sourceWidth, value.sourceHeight)
        require(value.coordinateSpace in setOf("original_pixels", "working_pixels")) { "原始参考图坐标无效" }
        if (value.coordinateSpace == "working_pixels") require(value.originalWidth <= 1536 && value.originalHeight <= 1536 &&
            value.originalWidth.toLong() * value.originalHeight <= 4_000_000) { "工作图片尺寸过大" }
        require(value.bounds.size == 4) { "人物选取范围无效" }
        val (left, top, right, bottom) = value.bounds
        require(left >= 0 && top >= 0 && right > left && bottom > top &&
            right <= value.originalWidth && bottom <= value.originalHeight) { "人物选取范围超出图片" }
        require(value.expectedVisualVersion >= 0 && value.needsConfirmation &&
            value.method in setOf("u2netp", "existing_alpha", "manual_selection", "manual_refinement")) { "人物预览信息无效" }
        return value
    }
}

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
