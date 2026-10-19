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
    val selfieStatus: String? = null, val borrowed: LocalBorrowedPhoto? = null,
    val preserveGeneratedText: Boolean = false, val readyCaption: String? = null,
    val photoRequested: Boolean = false, val standaloneMediaRequest: Boolean = false) {
    fun parse(raw: String): LocalMediaReply {
        val marks = Regex("\\[\\[ZHIYU_MEDIA_([^\\]\\r\\n]{1,160})\\]\\]").findAll(raw).toList()
        val one = marks.singleOrNull()
        val selected = requiredAttachmentId?.let { identifier -> candidates.singleOrNull { it.id == identifier } } ?: one?.takeIf { raw.substring(it.range.last + 1).isBlank() }
            ?.groupValues?.get(1)?.takeIf { it.startsWith("$nonce:") }?.removePrefix("$nonce:")
            ?.let { identifier -> candidates.singleOrNull { it.id == identifier } }
        val generated = raw.replace(Regex("\\[\\[ZHIYU_MEDIA_[^\\r\\n]*?(?:\\]\\]|$)"), "").trim()
        val falseSend = selfieRequested && selected == null && explicitlyClaimsPhotoSent(generated)
        val content = if (photoRequested && selected == null) withoutPhoto(generated) else if (preserveGeneratedText && !falseSend) generated else
            readyCaption?.takeIf { (selfieRequested || photoRequested) && selected != null } ?: unavailableMessage ?:
            if (falseSend) "这会儿照片还没准备好，等我弄好了再给你看。" else generated
        require(content.isNotBlank()) { "AI 这次没有返回文字，请稍后重试" }
        return LocalMediaReply(content, selected?.let(::listOf) ?: emptyList())
    }

    private fun withoutPhoto(generated: String): String {
        val pending = unavailableMessage ?: "这条还没有附上照片，我现在没有可发的合适图片。"
        if (!preserveGeneratedText) return pending
        val kept = Regex("[^，。！？!?；;\\r\\n]+[，。！？!?；;\\r\\n]*").findAll(generated).map { it.value }
            .filterNot { part ->
                val value = part.trim().trimEnd('，', '。', '！', '？', '!', '?', '；', ';')
                explicitlyClaimsPhotoSent(value) || Regex("^(?:(?:这张|那张)(?!(?:试卷|作业|表格|票|卡|纸))|图里|图中|图片里|图片中|照片里|照片中).+").matches(value) ||
                    Regex("^(?:我)?(?:马上|这就|再)?(?:给你发|发给你|发你|发过去|翻翻|翻来翻去|挑张|挑一张|选张).*").matches(value)
            }.joinToString("").trim()
        return if (kept.isBlank()) pending else "$kept\n$pending"
    }

    /** Guard only explicit standalone self-reports; quotations, negations and future plans remain intact. */
    private fun explicitlyClaimsPhotoSent(text: String): Boolean {
        val photo = "(?:(?:这张|那张|我的)?(?:照片|自拍|图片))?"
        val completed = "${photo}(?:我)?(?:已经|已|刚刚|刚)(?:给你)?(?:发|传)(?:给你|过去|出)?(?:了|啦|咯|哦)?"
        val delivered = "${photo}(?:我)?(?:发给你|发过去|传给你|传过去)(?:了|啦|咯|哦)"
        return text.split(Regex("[，。！？!?；;\\r\\n]")).any { clause ->
            val value = clause.replace(Regex("\\s+"), "")
            Regex("(?:$completed|$delivered)").matches(value)
        }
    }
}
