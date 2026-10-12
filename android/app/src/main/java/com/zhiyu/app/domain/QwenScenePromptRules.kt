package com.zhiyu.app.domain

import java.time.DateTimeException
import java.time.LocalDate

data class QwenScenePromptRequest(val chatId: String, val dayId: String, val sceneId: String,
    val slotTime: String, val activity: String, val tags: List<String>,
    val referenceSha256: String? = null, val visualVersion: Int = 0)

enum class QwenSceneReviewStatus { APPROVED, REJECTED, UNAVAILABLE, UNKNOWN }
enum class QwenSceneReviewPurpose { BACKGROUND, COMPOSITE, POV }
data class QwenSceneReview(val status: QwenSceneReviewStatus, val reason: String,
    val correction: String?, val imageSha256: String, val purpose: QwenSceneReviewPurpose = QwenSceneReviewPurpose.BACKGROUND) {
    /** The current native student generator is a background generator, not a reference-edit model. */
    val identityVerified: Boolean get() = false
}

object QwenScenePromptRules {
    const val MAX_PROMPT_BYTES = 2000
    private const val BACKGROUND = " Background only, no people, no faces, no limbs, no text, no logos, no watermarks."

    fun check(request: QwenScenePromptRequest) {
        MediaRules.path(request.chatId); MediaRules.path(request.sceneId)
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(request.dayId)) { "生活图日期无效" }
        try { LocalDate.parse(request.dayId) } catch (_: DateTimeException) { throw IllegalArgumentException("生活图日期无效") }
        require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(request.slotTime)) { "生活图时间无效" }
        require(request.visualVersion >= 0 && request.activity.length in 1..200 &&
            request.activity.none(Char::isISOControl) && request.tags.size in 1..16 &&
            request.tags.all { Regex("[a-z][a-z0-9_]{0,31}").matches(it) }) { "生活图场景信息无效" }
        LocalMemoryCodec.bytes(request.activity)
        request.referenceSha256?.let(ChatPhotoRules::digest)
    }

    fun metadata(request: QwenScenePromptRequest): String {
        check(request)
        val quote = AgnesImageJson::quote
        return "{\"chat_id\":${quote(request.chatId)},\"day_id\":${quote(request.dayId)},\"scene_id\":${quote(request.sceneId)}," +
            "\"slot_time\":${quote(request.slotTime)},\"timezone\":\"Asia/Shanghai\",\"activity\":${quote(request.activity)}," +
            "\"tags\":[${request.tags.joinToString(",", transform = quote)}],\"visual_version\":${request.visualVersion}," +
            "\"reference_sha256\":${request.referenceSha256?.let(quote) ?: "null"},\"fictional\":true}"
    }

    fun constrainPrompt(value: String): String {
        val text = value.trim()
        require(text.length in 8..MAX_PROMPT_BYTES && text.all { it.code in 32..126 }) { "生成描述必须是有长度限制的英文文本" }
        val result = if (text.endsWith(BACKGROUND)) text else text + BACKGROUND
        require(LocalMemoryCodec.bytes(result).size <= MAX_PROMPT_BYTES) { "生成描述超过 2000 UTF-8 字节" }
        return result
    }

    fun parsePrompt(response: String): String {
        val value = objectResponse(response)
        require(value.keys == setOf("prompt")) { "生成描述响应字段无效" }
        return constrainPrompt(value["prompt"] as? String ?: throw IllegalArgumentException("生成描述无效"))
    }

    fun parseReview(response: String, imageSha256: String,
        purpose: QwenSceneReviewPurpose = QwenSceneReviewPurpose.BACKGROUND): QwenSceneReview {
        ChatPhotoRules.digest(imageSha256)
        val value = objectResponse(response)
        require(value.keys == setOf("approved", "reason", "correction")) { "图片复审响应字段无效" }
        val approved = value["approved"] as? Boolean ?: throw IllegalArgumentException("图片复审结果无效")
        val reason = value["reason"] as? String ?: throw IllegalArgumentException("图片复审原因无效")
        require(reason.isNotBlank() && reason.length <= 320 && reason.none(Char::isISOControl)) { "图片复审原因无效" }
        LocalMemoryCodec.bytes(reason)
        val raw = (value["correction"] as? String ?: throw IllegalArgumentException("图片复审修正描述无效")).trim()
        require(!approved || raw.isEmpty()) { "图片复审结果与修正描述冲突" }
        val correction = raw.takeIf(String::isNotEmpty)?.let(::constrainPrompt)
        return QwenSceneReview(if (approved) QwenSceneReviewStatus.APPROVED else QwenSceneReviewStatus.REJECTED,
            reason, correction, imageSha256, purpose)
    }

    private fun objectResponse(response: String): Map<*, *> {
        require(LocalMemoryCodec.bytes(response).size in 1..8192) { "图片辅助响应大小无效" }
        return AgnesImageJson.parse(response) as? Map<*, *> ?: throw IllegalArgumentException("图片辅助响应格式无效")
    }
}
