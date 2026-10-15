package com.zhiyu.app.data.planar

/** Strict JSON for external archives: finite numbers, duplicate key rejection and bounded trees. */
object PlanarJson {
    private const val MAX_TEXT = 32 * 1024 * 1024
    private const val MAX_STRING = 12 * 1024 * 1024
    private const val MAX_ARRAY = 8192
    private const val MAX_NODES = 2_000_000
    fun parse(text: String, maxNodes:Int=MAX_NODES, maxArray:Int=MAX_ARRAY): Any? {
        require(text.length <= MAX_TEXT&&maxNodes in 1..MAX_NODES&&maxArray in 1..MAX_ARRAY) { "PLANAR_JSON_BUDGET" }
        return Reader(text,maxNodes,maxArray).parse()
    }
    fun encode(value: Any?): String = buildString { write(value, 0, EncodeBudget()) }.also {
        require(it.length <= MAX_TEXT) { "PLANAR_JSON_BUDGET" }
    }

    private class EncodeBudget(var nodes: Int = 0)
    private fun StringBuilder.write(value: Any?, depth: Int, budget: EncodeBudget) {
        require(depth <= 16 && ++budget.nodes <= MAX_NODES && length <= MAX_TEXT) { "PLANAR_JSON_STRUCTURE_BUDGET" }
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Number -> {
                val numeric = value.toString()
                require(numeric.length <= 128 && value.toDouble().isFinite() && NUMBER.matches(numeric) && numeric.toDouble().isFinite()) { "PLANAR_NONFINITE_JSON" }; append(numeric)
            }
            is String -> {
                validateString(value); append('"')
                value.forEach { char -> require(length <= MAX_TEXT) { "PLANAR_JSON_BUDGET" }; when (char) {
                    '"' -> append("\\\""); '\\' -> append("\\\\")
                    '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                    else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
                } }; append('"')
            }
            is Map<*, *> -> {
                require(value.size <= 128 && value.keys.all { it is String }) { "PLANAR_JSON_KEY" }
                append('{')
                value.keys.map { it as String }.sorted().forEachIndexed { index, key ->
                    if (index > 0) append(','); write(key, depth + 1, budget); append(':'); write(value[key], depth + 1, budget)
                }; append('}')
            }
            is List<*> -> {
                require(value.size <= MAX_ARRAY) { "PLANAR_JSON_ARRAY_BUDGET" }
                append('['); value.forEachIndexed { index, item -> if (index > 0) append(','); write(item, depth + 1, budget) }; append(']')
            }
            else -> throw IllegalArgumentException("PLANAR_JSON_TYPE")
        }
    }

    private fun validateString(value: String) {
        require(value.length <= MAX_STRING) { "PLANAR_JSON_STRING_BUDGET" }
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "PLANAR_JSON_UNICODE" }
            else require(!char.isLowSurrogate()) { "PLANAR_JSON_UNICODE" }
        }
    }

    private class Reader(private val text: String,private val maxNodes:Int,private val maxArray:Int) {
        private var index = 0
        private var nodes = 0
        fun parse(): Any? {
            val result = value(0); whitespace()
            require(index == text.length) { "PLANAR_JSON_TRAILING_DATA" }
            return result
        }
        private fun value(depth: Int): Any? {
            whitespace()
            require(depth <= 16 && ++nodes <= maxNodes && index < text.length) { "PLANAR_JSON_STRUCTURE_BUDGET" }
            return when (text[index]) {
                '{' -> objectValue(depth + 1); '[' -> arrayValue(depth + 1); '"' -> string()
                't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun objectValue(depth: Int): Map<String, Any?> {
            index++; whitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                whitespace(); require(index < text.length && text[index] == '"') { "PLANAR_JSON_KEY" }
                require(result.size < 128) { "PLANAR_JSON_OBJECT_BUDGET" }
                val key = string(); require(!result.containsKey(key)) { "PLANAR_DUPLICATE_JSON_KEY" }
                whitespace(); require(consume(':')) { "PLANAR_JSON_COLON" }
                result[key] = value(depth); whitespace()
                if (consume('}')) return result
                require(consume(',')) { "PLANAR_JSON_COMMA" }
            }
        }
        private fun arrayValue(depth: Int): List<Any?> {
            index++; whitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                require(result.size < maxArray) { "PLANAR_JSON_ARRAY_BUDGET" }
                result.add(value(depth)); whitespace()
                if (consume(']')) return result
                require(consume(',')) { "PLANAR_JSON_COMMA" }
            }
        }
        private fun string(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString().also(::validateString)
                require(char.code >= 32 && result.length < MAX_STRING) { "PLANAR_JSON_STRING" }
                result.append(if (char == '\\') escape() else char)
            }
            throw IllegalArgumentException("PLANAR_JSON_UNTERMINATED_STRING")
        }
        private fun escape(): Char {
            require(index < text.length) { "PLANAR_JSON_ESCAPE" }
            return when (val char = text[index++]) {
                '"', '\\', '/' -> char; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> {
                    require(index + 4 <= text.length) { "PLANAR_JSON_ESCAPE" }
                    val hex = text.substring(index, index + 4)
                    require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "PLANAR_JSON_ESCAPE" }
                    index += 4; hex.toInt(16).toChar()
                }
                else -> throw IllegalArgumentException("PLANAR_JSON_ESCAPE")
            }
        }
        private fun number(): Double {
            val match = NUMBER.find(text, index)
            require(match != null && match.range.first == index && match.range.last - match.range.first < 128) { "PLANAR_JSON_NUMBER" }
            val result = match.value.toDouble()
            require(result.isFinite()) { "PLANAR_NONFINITE_JSON" }
            index = match.range.last + 1; return result
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, index)) { "PLANAR_JSON_LITERAL" }; index += word.length; return result
        }
        private fun whitespace() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun consume(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
    }
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
