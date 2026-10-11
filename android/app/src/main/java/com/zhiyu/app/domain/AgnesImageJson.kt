package com.zhiyu.app.domain

/** Bounded JSON for large Base64 image responses, rejecting duplicates and invalid Unicode. */
internal object AgnesImageJson {
    fun parse(text: String): Any? = Reader(text).parse()
    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { char -> when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        } }
        append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0
        private var nodes = 0
        fun parse(): Any? {
            require(text.length <= AgnesImageProtocol.MAX_RESPONSE_BYTES)
            val result = value(0)
            whitespace()
            require(index == text.length)
            return result
        }
        private fun value(depth: Int): Any? {
            whitespace()
            require(depth <= 8 && ++nodes <= 256 && index < text.length)
            return when (text[index]) {
                '{' -> objectValue(depth + 1); '[' -> arrayValue(depth + 1)
                '"' -> string(); 't' -> literal("true", true)
                'f' -> literal("false", false); 'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun objectValue(depth: Int): Map<String, Any?> {
            index++; whitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                whitespace(); require(index < text.length && text[index] == '"')
                val key = string()
                require(!result.containsKey(key))
                whitespace(); require(consume(':'))
                result[key] = value(depth)
                whitespace()
                if (consume('}')) return result
                require(consume(','))
            }
        }
        private fun arrayValue(depth: Int): List<Any?> {
            index++; whitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                require(result.size < 32)
                result.add(value(depth)); whitespace()
                if (consume(']')) return result
                require(consume(','))
            }
        }
        private fun string(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString().also { LocalMemoryCodec.bytes(it) }
                require(char.code >= 32 && result.length < AgnesImageProtocol.MAX_RESPONSE_BYTES)
                result.append(if (char == '\\') escape() else char)
            }
            throw IllegalArgumentException()
        }
        private fun escape(): Char {
            require(index < text.length)
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char
                'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> {
                    require(index + 4 <= text.length)
                    val hex = text.substring(index, index + 4)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    index += 4; hex.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException()
            }
        }
        private fun number(): Double {
            val start = index
            while (index < text.length && text[index] in "-+0123456789.eE") {
                require(index - start < 128); index++
            }
            val raw = text.substring(start, index)
            require(raw.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
            return raw.toDouble().also { require(it.isFinite()) }
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, index)); index += word.length
            return result
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun consume(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
    }
}
