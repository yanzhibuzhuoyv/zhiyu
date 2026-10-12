package com.zhiyu.app.data.local.qwen

import java.util.UUID

data class QwenNativeResult(val tokens: Int = 0, val metadata: String? = null)
data class QwenNativeHello(val runtimeIdentity: String, val pid: Int, val uid: Int, val process: String)

/** Bounded one-request protocol. Neither argv nor arbitrary native filesystem paths cross Binder. */
object QwenNativeServiceProtocol {
    const val HELLO = 1
    const val RUN = 2
    const val STOP = 3
    const val HELLO_REPLY = 101
    const val RESULT = 102
    const val ERROR = 103
    const val MAX_METADATA = 4096
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val STOP_TIMEOUT_MS = 5_000L
    val stages = setOf("text", "sample", "decode", "inspect")

    fun requireStage(stage: String) { require(stage in stages) { "未知本机制图阶段" } }
    fun suffix(stage: String): String { requireStage(stage); return if (stage == "text") ":qwen_text" else ":qwen_image" }
    fun request(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value) { "无效的制图请求标识" }
        return value
    }
    fun jobId(value: String): String {
        require(Regex("[a-f0-9]{64}").matches(value)) { "无效的制图任务标识" }
        return value
    }
    fun requireState(stage: String, state: String) {
        requireStage(stage)
        val allowed = when (stage) {
            "text" -> setOf("prepared")
            "sample" -> setOf("encoded")
            "decode" -> setOf("sampled")
            else -> setOf("encoded", "sampled", "decoded", "approved", "rejected", "review_unknown")
        }
        require(state in allowed) { "制图任务阶段已变化，不能使用旧任务" }
    }
    fun budgetMs(stage: String): Long {
        requireStage(stage)
        return when (stage) { "text" -> 300_000L; "sample" -> 3_900_000L; "decode" -> 900_000L; else -> 15_000L }
    }
}
