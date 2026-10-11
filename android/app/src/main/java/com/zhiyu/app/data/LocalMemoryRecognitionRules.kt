package com.zhiyu.app.data

import java.security.MessageDigest
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Semantic model supplies candidates; only grounded original pointers survive these guards. */
object LocalMemoryRecognitionRules {
    const val VERSION = "native-semantic-v1"
    const val MAX_WINDOW = 16000
    const val MAX_CLAIMS = 8
    private val kinds = setOf("profile", "preference", "wish", "habit", "promise")
    private val undone = Regex("明天|后天|下周|准备|计划|打算|还没|未完成|没有完成|未兑现|tomorrow|not yet", RegexOption.IGNORE_CASE)
    private val fulfilled = Regex("已经|已完成|已兑现|完成了|兑现了|履行了|去了|吃过|做完|fulfilled|done", RegexOption.IGNORE_CASE)
    private val cancelled = Regex("取消|撤销|作废|cancelled|canceled", RegexOption.IGNORE_CASE)
    private val notCancelled = Regex("不能取消|没有取消|未取消|不想取消|not cancelled|not canceled", RegexOption.IGNORE_CASE)
    private val correction = Regex("更正|纠正|改成|不对|不是|记错|说错|误记|其实是|correct|actually", RegexOption.IGNORE_CASE)
    private val keep = Regex("一直记住|永远|长期|以后.*记住|别忘了|长久|always remember|remember forever", RegexOption.IGNORE_CASE)

    fun window(source: LocalMemoryOriginal, offset: Int): LocalMemoryWindow {
        require(offset in 0 until source.text.length)
        require(offset == 0 || !Character.isLowSurrogate(source.text[offset]) || !Character.isHighSurrogate(source.text[offset - 1]))
        var end = minOf(source.text.length, offset + MAX_WINDOW)
        if (end < source.text.length && Character.isHighSurrogate(source.text[end - 1]) && Character.isLowSurrogate(source.text[end])) end--
        return LocalMemoryWindow(source, offset, end)
    }

    fun ground(window: LocalMemoryWindow, raw: LocalMemoryRawClaim): LocalMemoryAnnotation? {
        validate(raw)
        if (raw.confidence < .85 || raw.polarity == "uncertain" || raw.subject != window.source.role ||
            window.source.role == "assistant" && raw.kind != "promise") return null
        if (!resolved(raw)) return null
        val (start, end) = locate(window.text, raw)
        val source = window.source
        val topic = if (raw.topicKey in setOf("birthday", "生日", "birthdate", "profile:birthday", "user:生日")) "user:birthday" else raw.topicKey
        require(topic != "user:birthday" || raw.kind == "profile")
        val basis = raw.dateBasis?.takeIf { raw.quote.contains(if (it == "lunar") Regex("农历|阴历|lunar", RegexOption.IGNORE_CASE) else Regex("公历|阳历|gregorian", RegexOption.IGNORE_CASE)) }
        val absoluteStart = window.start + start
        val absoluteEnd = window.start + end
        val id = sha("${source.owner}\u0000${source.messageId}\u0000$VERSION\u0000$absoluteStart:$absoluteEnd:${raw.kind}:$topic").take(32)
        return LocalMemoryAnnotation(id, source.owner, source.conversationId, source.messageId, source.role, source.createdAt,
            source.sha256, source.revision, absoluteStart, absoluteEnd, sha(raw.quote), raw.kind, raw.subject, raw.polarity,
            topic, raw.confidence, source.role == "user" && raw.explicitKeep && keep.containsMatchIn(raw.quote),
            raw.polarity == "deny" || raw.correction && correction.containsMatchIn(raw.quote),
            if (raw.kind == "promise") raw.promiseState ?: "pending" else null, basis, raw.eventDate)
    }

    private fun validate(raw: LocalMemoryRawClaim) {
        require(raw.quote.isNotEmpty() && raw.quote.length <= MAX_WINDOW && raw.kind in kinds)
        require(raw.subject in setOf("user", "assistant", "other") && raw.polarity in setOf("affirm", "deny", "uncertain"))
        require(raw.confidence.isFinite() && raw.confidence in 0.0..1.0)
        require(Regex("[\\p{L}\\p{N}_:-]{1,80}").matches(raw.topicKey))
        require(raw.promiseState in setOf(null, "pending", "fulfilled", "cancelled"))
        require(raw.kind == "promise" || raw.promiseState == null)
        require(raw.dateBasis in setOf(null, "gregorian", "lunar"))
        require(raw.eventDate == null || raw.eventDate.length in 1..64 && raw.quote.contains(raw.eventDate) && !raw.eventDate.contains("://"))
    }

    private fun locate(text: String, raw: LocalMemoryRawClaim): Pair<Int, Int> {
        require((raw.start == null) == (raw.end == null))
        val start = raw.start ?: text.indexOf(raw.quote).also {
            require(it >= 0 && text.indexOf(raw.quote, it + 1) < 0) { "语义原话片段缺失或位置不唯一" }
        }
        val end = raw.end ?: start + raw.quote.length
        require(start >= 0 && end > start && end <= text.length && text.substring(start, end) == raw.quote) { "语义标注位置与原文不符" }
        require(boundary(text, start) && boundary(text, end)) { "语义标注不能截断原文字符" }
        return start to end
    }

    private fun boundary(text: String, index: Int) = index == 0 || index == text.length ||
        !Character.isHighSurrogate(text[index - 1]) || !Character.isLowSurrogate(text[index])

    private fun resolved(raw: LocalMemoryRawClaim): Boolean = when (raw.promiseState) {
        "fulfilled" -> !undone.containsMatchIn(raw.quote) && fulfilled.containsMatchIn(raw.quote)
        "cancelled" -> !undone.containsMatchIn(raw.quote) && cancelled.containsMatchIn(raw.quote) && !notCancelled.containsMatchIn(raw.quote)
        else -> true
    }

    fun verify(source: LocalMemoryOriginal, annotation: LocalMemoryAnnotation) {
        require(annotation.owner == source.owner && annotation.conversationId == source.conversationId && annotation.messageId == source.messageId)
        require(annotation.role == source.role && annotation.createdAt == source.createdAt && annotation.sourceSha == source.sha256 && annotation.sourceRevision == source.revision)
        require(annotation.start >= 0 && annotation.end in annotation.start + 1..source.text.length && annotation.end - annotation.start <= MAX_WINDOW)
        val quote = source.text.substring(annotation.start, annotation.end)
        val raw = LocalMemoryRawClaim(quote, annotation.kind, annotation.subject, annotation.polarity, annotation.topicKey,
            annotation.confidence, annotation.explicitKeep, annotation.correction, annotation.promiseState,
            annotation.dateBasis, annotation.eventDate, 0, quote.length)
        require(ground(LocalMemoryWindow(source, annotation.start, annotation.end), raw) == annotation) { "记忆标注无法对应完整原文" }
    }

    fun sha(text: String): String = try {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("语义原文编码无效") }
}
