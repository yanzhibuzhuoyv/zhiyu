package com.zhiyu.app.domain

object CurrentClockClaims {
    class Stream(private val runtime: ConversationRuntime) {
        private var pending = ""
        private var finished = false
        private val literals = LiteralContext()
        private var recent = ""
        private val starts = listOf("现在", "此刻", "这会儿", "眼下", "都", "已经")
        fun append(text: String): String {
            check(!finished)
            pending += text
            val boundary = pending.indexOfLast { it in "。！？?!\n" } + 1
            val output = StringBuilder()
            if (boundary > 0) output.append(flush(boundary))
            val possibleClock = starts.any { pending.contains(it) ||
                (1 until it.length).any { length -> pending.endsWith(it.take(length)) } }
            // Ordinary public text keeps its original low-latency delivery. Only a possible
            // present-clock claim or an unfinished literal delimiter needs more input.
            if (pending.isNotEmpty() && (pending.length > 2048 || !possibleClock && !pending.endsWith('`') && !pending.endsWith('~') && !pending.endsWith('\'')))
                output.append(flush(pending.length))
            return output.toString()
        }
        private fun flush(end: Int): String {
            val complete = pending.substring(0, end)
            pending = pending.substring(end)
            val result = correct(complete, runtime, literals, recent)
            recent = (recent + complete).takeLast(48)
            return result
        }
        fun finish(): String {
            check(!finished); finished = true
            return flush(pending.length)
        }
    }
    private val claim = Regex("(现在|此刻|这会儿|眼下|都|已经)((?:都|已经|才|是|到|还是|\\s){0,4})(凌晨|早上|清晨|上午|中午|下午|晚上|深夜|夜里)?([零〇一二两三四五六七八九十]{1,3}|\\d{1,2})(?:点|时|[:：][0-5]\\d)(?:[0-5]?\\d分?|[一二三四五六七八九十]{1,3}分|半|一刻|三刻)?(?=了|啦|呀|呢|整|钟|[，,。！!？?\\s]|$)")
    private val otherContext = Regex("时区|纽约|伦敦|东京|日本|美国|英国|UTC|假如|故事")
    private val reported = Regex("你说|他说|她说|如果|假如|故事|昨天|昨晚|明天|到时|[‘’“”「」\"`]")
    fun correct(answer: String, runtime: ConversationRuntime): String = correct(answer, runtime, LiteralContext())
    private fun correct(answer: String, runtime: ConversationRuntime, literals: LiteralContext, recent: String = ""): String {
        val literalMask = literals.scan(answer)
        if (otherContext.containsMatchIn(runtime.latestUserText)) return answer
        val local = runtime.now.atZone(runtime.zone)
        return claim.replace(answer) { match ->
            if (match.range.any { literalMask[it] }) return@replace match.value
            val prefix = (recent + answer.substring(0, match.range.first)).takeLast(48)
                .substringAfterLast('。').substringAfterLast('\n')
            if (reported.containsMatchIn(prefix)) return@replace match.value
            val part = match.groupValues[3]
            val raw = hour(match.groupValues[4]) ?: return@replace match.value
            val claimed = when (part) {
                "下午", "晚上", "深夜", "夜里" -> if (raw in 1..11) raw + 12 else raw
                "中午" -> if (raw in 1..3) raw + 12 else raw
                "凌晨", "早上", "清晨", "上午" -> if (raw == 12) 0 else raw
                else -> raw
            }
            if (claimed == local.hour || part.isEmpty() && claimed % 12 == local.hour % 12) return@replace match.value
            val actualPart = when (local.hour) { in 0..4 -> "凌晨"; in 5..10 -> "上午"; in 11..13 -> "中午"; in 14..17 -> "下午"; else -> "晚上" }
            match.groupValues[1] + match.groupValues[2] + actualPart + local.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    /** Literal ownership survives streaming sentence/newline flushes; quoted originals stay exact. */
    private class LiteralContext {
        private val quotes = ArrayList<Char>()
        private var escaped = false
        private var codeMarker: Char? = null
        private var codeLength = 0
        private var blockCode = false
        private var leadingSpace = true
        private var quoteLine = false
        private var previous: Char? = null
        private val opening = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '"' to '"', '\'' to '\'')

        fun scan(text: String): BooleanArray {
            val literalMask = BooleanArray(text.length)
            var index = 0
            while (index < text.length) {
                val count = consume(text, index, literalMask)
                for (position in index until index + count) {
                    if (text[position] == '\n') { leadingSpace = true; quoteLine = false; escaped = false }
                    else if (!text[position].isWhitespace()) leadingSpace = false
                    previous = text[position]
                }
                index += count
            }
            return literalMask
        }

        private fun consume(text: String, index: Int, literalMask: BooleanArray): Int {
            val char = text[index]
            if (quoteLine) { literalMask[index] = true; return 1 }
            if (quotes.isNotEmpty()) {
                literalMask[index] = true
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == quotes.last() && !apostrophe(text, index)) quotes.removeAt(quotes.lastIndex)
                else if (!apostrophe(text, index)) opening[char]?.let { quotes.add(it) }
                return 1
            }
            var run = 1
            if (char == '`' || char == '~') while (index + run < text.length && text[index + run] == char) run++
            if (codeMarker != null) {
                literalMask.fill(true, index, index + run)
                if (char == codeMarker && (if (blockCode) leadingSpace && run >= codeLength else run == codeLength)) codeMarker = null
                return run
            }
            if (char == '>' && leadingSpace) { quoteLine = true; literalMask[index] = true; return 1 }
            if (char == '`' || char == '~' && leadingSpace && run >= 3) {
                codeMarker = char; codeLength = run; blockCode = leadingSpace && run >= 3
                literalMask.fill(true, index, index + run)
                return run
            }
            // An apostrophe inside an English word is not an opening quotation mark.
            if (apostrophe(text, index)) return 1
            opening[char]?.let { quotes.add(it); literalMask[index] = true }
            return 1
        }

        private fun apostrophe(text: String, index: Int) = text[index] == '\'' &&
            index + 1 < text.length && (text.getOrNull(index - 1) ?: previous)?.isLetterOrDigit() == true &&
            text[index + 1].isLetterOrDigit()
    }
    private fun hour(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { hour -> hour in 0..23 } }
        fun digit(c: Char) = when (c) { '两' -> 2; '〇' -> 0; else -> "零一二三四五六七八九".indexOf(c).takeIf { it >= 0 } }
        if ('十' !in value) return value.singleOrNull()?.let(::digit)
        val pieces = value.split('十'); if (pieces.size != 2) return null
        val tens = if (pieces[0].isEmpty()) 1 else pieces[0].singleOrNull()?.let(::digit) ?: return null
        val ones = if (pieces[1].isEmpty()) 0 else pieces[1].singleOrNull()?.let(::digit) ?: return null
        return (tens * 10 + ones).takeIf { it in 0..23 }
    }
}
