package com.zhiyu.app.data

/** Exact fields and primitive types; successful empty recognition remains an empty observation set. */
object LocalMemoryRecognitionJson {
    private val required = setOf("quote", "kind", "subject", "polarity", "topic_key", "confidence", "explicit_keep", "correction", "promise_state", "date_basis", "event_date")
    fun parse(output: String, window: LocalMemoryWindow): List<LocalMemoryAnnotation> {
        val root = LocalMemoryStrictJson.parse(output) as? Map<*, *> ?: throw IllegalArgumentException("语义响应必须为JSON对象")
        require(root.keys == setOf("claims")) { "语义响应字段无效" }
        val values = root["claims"] as? List<*> ?: throw IllegalArgumentException("语义标注必须为数组")
        require(values.size <= LocalMemoryRecognitionRules.MAX_CLAIMS)
        val claims = values.map { value ->
            val fields = value as? Map<*, *> ?: throw IllegalArgumentException("语义标注字段无效")
            require(fields.keys.containsAll(required) && fields.keys.all { it in required || it in setOf("start", "end") })
            LocalMemoryRecognitionRules.ground(window, raw(fields))
        }.filterNotNull()
        require(claims.map { it.id }.distinct().size == claims.size) { "语义标注重复" }
        return claims
    }
    private fun raw(fields: Map<*, *>): LocalMemoryRawClaim = LocalMemoryRawClaim(
        string(fields, "quote"), string(fields, "kind"), string(fields, "subject"), string(fields, "polarity"),
        string(fields, "topic_key"), fields["confidence"] as? Double ?: throw IllegalArgumentException("语义置信度无效"),
        fields["explicit_keep"] as? Boolean ?: throw IllegalArgumentException("语义保存标记无效"),
        fields["correction"] as? Boolean ?: throw IllegalArgumentException("语义更正标记无效"),
        nullable(fields, "promise_state"), nullable(fields, "date_basis"), nullable(fields, "event_date"),
        integer(fields["start"]), integer(fields["end"]),
    )
    private fun string(fields: Map<*, *>, key: String) = fields[key] as? String ?: throw IllegalArgumentException("语义字符串字段无效")
    private fun nullable(fields: Map<*, *>, key: String) = if (fields[key] == null) null else string(fields, key)
    private fun integer(value: Any?): Int? {
        if (value == null) return null
        require(value is Double && value in 0.0..LocalMemoryRecognitionRules.MAX_WINDOW.toDouble() && value % 1.0 == 0.0)
        return value.toInt()
    }
}
