package com.zhiyu.app.data

/** Small strict JSON reader for low-trust model output; duplicate fields cannot overwrite evidence. */
internal object LocalMemoryStrictJson {
    fun parse(text: String): Any? {
        require(text.length <= 65536) { "语义响应过长" }
        return Reader(text).parse()
    }
    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { char -> when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32 || char == '<') append("\\u%04x".format(char.code)) else append(char)
        } }
        append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0
        private var nodes = 0
        fun parse(): Any? {
            val value = value(0)
            whitespace()
            require(index == text.length) { "语义响应不是完整JSON" }
            return value
        }
        private fun value(depth: Int): Any? {
            whitespace()
            require(depth <= 16 && ++nodes <= 512 && index < text.length) { "语义JSON结构无效" }
            return when (text[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun objectValue(depth: Int): Map<String, Any?> {
            index++
            whitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                whitespace()
                require(index < text.length && text[index] == '"') { "语义JSON字段必须加引号" }
                val key = string()
                require(!result.containsKey(key)) { "语义JSON字段重复" }
                whitespace(); require(consume(':'))
                result[key] = value(depth)
                whitespace()
                if (consume('}')) return result
                require(consume(','))
            }
        }
        private fun arrayValue(depth: Int): List<Any?> {
            index++
            whitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                require(result.size < 64) { "语义JSON数组过长" }
                result.add(value(depth))
                whitespace()
                if (consume(']')) return result
                require(consume(','))
            }
        }
        private fun string(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') {
                    val string = result.toString()
                    LocalMemoryRecognitionRules.sha(string) // strict UTF8 validation, including escaped surrogate pairs.
                    return string
                }
                require(char.code >= 32) { "语义JSON字符串含裸控制字符" }
                result.append(if (char == '\\') escape() else char)
            }
            throw IllegalArgumentException("语义JSON字符串未结束")
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
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException("语义JSON转义无效")
            }
        }
        private fun number(): Double {
            val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, index)
            require(match != null && match.range.first == index) { "语义JSON数值无效" }
            val result = match.value.toDouble()
            require(result.isFinite()) { "语义JSON数值必须有限" }
            index = match.range.last + 1
            return result
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, index))
            index += word.length
            return result
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun consume(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
    }
}
