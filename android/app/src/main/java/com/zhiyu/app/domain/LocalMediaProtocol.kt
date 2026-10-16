package com.zhiyu.app.domain

data class LocalMediaReply(val content: String, val attachments: List<MediaAttachment>)
object LocalMediaFreshness {
    fun accept(reply: LocalMediaReply, currentVersion: Int, currentDay: String, personIds: Set<String>, versionedIds: Set<String> = emptySet()): LocalMediaReply {
        require(currentVersion >= 0 && Regex("\\d{4}-\\d{2}-\\d{2}").matches(currentDay))
        return reply.copy(attachments = reply.attachments.filter { asset ->
            asset.dayId == currentDay &&
                (asset.id !in personIds || currentVersion > 0 && asset.visualVersion == currentVersion) &&
                (asset.id !in versionedIds || asset.visualVersion == currentVersion)
        })
    }
}

data class LocalMediaPlan(val prompt: String, val candidates: List<MediaAttachment>, val nonce: String,
    val selfieRequested: Boolean = false, val requiredAttachmentId: String? = null, val unavailableMessage: String? = null,
    val selfieStatus: String? = null) {
    fun parse(raw: String): LocalMediaReply {
        val marks = Regex("\\[\\[ZHIYU_MEDIA_([^\\]\\r\\n]{1,160})\\]\\]").findAll(raw).toList()
        val one = marks.singleOrNull()
        val selected = requiredAttachmentId?.let { identifier -> candidates.singleOrNull { it.id == identifier } } ?: one?.takeIf { raw.substring(it.range.last + 1).isBlank() }
            ?.groupValues?.get(1)?.takeIf { it.startsWith("$nonce:") }?.removePrefix("$nonce:")
            ?.let { identifier -> candidates.singleOrNull { it.id == identifier } }
        val content = unavailableMessage ?: raw.replace(Regex("\\[\\[ZHIYU_MEDIA_[^\\r\\n]*?(?:\\]\\]|$)"), "").trim()
        require(content.isNotBlank()) { "AI 这次没有返回文字，请稍后重试" }
        return LocalMediaReply(content, selected?.let(::listOf) ?: emptyList())
    }
}
