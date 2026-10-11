package com.zhiyu.app.domain

import java.text.Normalizer
import java.util.Locale

data class LocalRecall(val messageId: String, val role: String, val text: String, val createdAt: String, val tier: String, val score: Double,
    val startOffset: Int = 0, val endOffset: Int = text.length, val originalChars: Int = text.length,
    val evidenceState:String = "original",val correctionOf:List<String> = emptyList())
data class LocalTextPart(val offset: Int, val text: String)

/** Local lexical retrieval: Chinese bigrams and Unicode words, without an extra model request. */
object LocalMemoryRules {
    private val stop = setOf("我的", "你的", "你记", "记得", "知道", "告诉", "一下", "这个", "那个", "之前", "上次", "那次", "你说", "说过", "我想", "想吃", "可以", "好吗", "什么", "怎么")
    fun shouldRetrieve(text: String) = listOf("记得", "生日", "之前", "以前", "上次", "那次", "约好", "答应", "承诺", "喜欢", "爱好", "想吃", "忌口", "叫什么", "哪天").any(text::contains)
    fun terms(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = linkedSetOf<String>()
        val word = StringBuilder()
        var previousHan: String? = null
        fun flush() { if (word.isNotEmpty()) { result.add(word.toString().take(64)); word.setLength(0) } }
        normalized.codePoints().forEach { point ->
            if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
                flush()
                val han = String(Character.toChars(point))
                result.add(han)
                previousHan?.let { result.add(it + han) }
                previousHan = han
            } else {
                previousHan = null
                if (Character.isLetterOrDigit(point) || point == '_'.code) word.appendCodePoint(point) else flush()
            }
        }
        flush()
        return result.toList()
    }
    fun queryTerms(text: String): List<String> {
        if (text.length <= 1000) return eligible(terms(text)).take(40)
        // Every supported input region is scanned; overlap preserves boundary-spanning bigrams.
        val windows = parts(text,1024).map { part ->
            val start = if (part.offset == 0) 0 else text.offsetByCodePoints(part.offset,-1)
            eligible(terms(text.substring(start,part.offset + part.text.length)))
        }
        val all = windows.flatten().distinct()
        if (all.size <= 40) return all
        val selected = linkedSetOf<String>()
        fun add(term:String) { if (selected.size < 40) selected.add(term) }
        windows.first().take(2).forEach(::add)
        windows.last().takeLast(4).forEach(::add)
        // Names, numbers, and word identifiers can appear anywhere in a dense Chinese input.
        all.asSequence().filter { term -> term.codePoints().anyMatch {
            Character.UnicodeScript.of(it) != Character.UnicodeScript.HAN
        } }.take(12).forEach(::add)
        val queues = windows.map { (it.takeLast(2) + it.take(2) + it).distinct() }.asReversed()
        var offset = 0
        while (selected.size < 40 && queues.any { offset < it.size }) {
            queues.forEach { if (offset < it.size) add(it[offset]) }
            offset++
        }
        return selected.toList()
    }
    private fun eligible(words:List<String>):List<String> = words.filter { it !in stop &&
        (it.codePointCount(0,it.length) > 1 || it in setOf("猫","狗","鱼","糖")) }
    fun matchQuery(text: String): String = queryTerms(text).joinToString(" OR ") { "\"$it\"" }
    fun parts(text: String, chunkPoints: Int = 1024): List<LocalTextPart> {
        require(chunkPoints in 64..4096)
        val result = mutableListOf<LocalTextPart>()
        var offset = 0
        var remaining = text.codePointCount(0, text.length)
        while (remaining > 0) {
            val count = minOf(chunkPoints, remaining)
            val end = text.offsetByCodePoints(offset, count)
            result.add(LocalTextPart(offset, text.substring(offset, end)))
            offset = end
            remaining -= count
        }
        return result
    }
    fun recent(messages: List<Message>, budget: Int = 48000): List<Message> {
        require(budget >= 128)
        var used = 0
        val selected = mutableListOf<Message>()
        for (message in messages.asReversed().take(40)) {
            val available = budget - used
            if (message.content.length <= available) {
                selected.add(message); used += message.content.length
            } else {
                if (available < 160) break
                val length = minOf(8192, available - 150)
                var start = message.content.length - length
                if (start < message.content.length && Character.isLowSurrogate(message.content[start])) start++
                val excerpt = "[连续原文节选；作者=${message.role}；消息=${message.id}；UTF16范围=$start..${message.content.length}]\n" + message.content.substring(start)
                if (excerpt.length > available) break
                selected.add(message.copy(content = excerpt)); used += excerpt.length
            }
        }
        return selected.asReversed()
    }
    fun score(query: String, original: String, role: String, tier: String): Double {
        val queryTerms = queryTerms(query)
        val words = terms(original).toSet()
        val overlap = queryTerms.count { it in words }.toDouble()
        val tierWeight = when (tier) { "permanent" -> 2.0; "long" -> 1.4; else -> 1.0 }
        return overlap * tierWeight * (if (role == "user") 1.3 else 1.0)
    }
}
