package com.zhiyu.app.domain

/** Every original keeps its own durable request ID and memory record, even when one answer covers it. */
data class QueuedTextInput(val requestId: String, val content: String)
data class QueuedTextSubmission(val requestId: String, val message: Message)
data class QueuedTextBatchResult(val submitted: List<QueuedTextSubmission>, val assistant: Message)

object QueuedTextBatchRules {
    const val MAX_INPUTS = 32
    const val MAX_CONTENT_CHARS = 32_000

    fun check(inputs: List<QueuedTextInput>): List<QueuedTextInput> {
        require(inputs.size in 1..MAX_INPUTS && inputs.map { it.requestId }.distinct().size == inputs.size) {
            "连续消息数量或请求标识无效"
        }
        require(inputs.sumOf { it.content.length.toLong() } <= MAX_CONTENT_CHARS) { "这组连续消息过长，请分开发送" }
        inputs.forEach {
            require(Regex("[a-f0-9]{32}").matches(it.requestId) && it.content.isNotBlank() && it.content.length <= 16_000) {
                "连续消息原文或请求标识无效"
            }
            LocalMemoryCodec.bytes(it.content)
        }
        return inputs.map { it.copy() }
    }
}
