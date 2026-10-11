package com.zhiyu.app.data

import java.security.MessageDigest

/** Fields received from one isolated decoder; no Android or file access. */
internal data class PdfHostReply(
    val kind: Int, val request: String?, val text: String? = null,
    val sequence: Int = -1, val total: Long = -1, val sha: String? = null,
    val pid: Int = -1, val uid: Int = -1,
)

/** Main-handler confined; the host's completion latch publishes this state to its caller. */
internal class PdfHostCollector(
    private val request: String, private val hostPid: Int, private val hostUid: Int,
    private val maxText: Int = MAX_TEXT,
) {
    companion object {
        const val TEXT = 2
        const val COMPLETE = 3
        const val FAILURE = 4
        const val MAX_CHUNK = 16384
        const val MAX_TEXT = 2_000_000
    }

    init { require(maxText in 1..MAX_TEXT) }
    private val result = StringBuilder()
    private val digest = MessageDigest.getInstance("SHA-256")
    private var sequence = 0
    var failure: String? = null
        private set
    var ready: Boolean = false
        private set
    val finished: Boolean get() = ready || failure != null

    fun accept(reply: PdfHostReply) {
        if (reply.request != request || finished) return
        when (reply.kind) {
            TEXT -> append(reply)
            COMPLETE -> complete(reply)
            FAILURE -> failure = "PDF 无法安全读取，请转为文本后导入"
            else -> failure = "PDF 解析返回了无效结果"
        }
    }

    fun content(): String {
        check(ready && failure == null) { "PDF 正文尚未通过完整性校验" }
        return result.toString()
    }

    private fun append(reply: PdfHostReply) {
        val text = reply.text
        if (text == null || text.length > MAX_CHUNK || result.length.toLong() + text.length > maxText ||
            reply.sequence != sequence) {
            failure = "PDF 正文过长或格式无效"
            return
        }
        result.append(text)
        sequence++
        text.forEach { char ->
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
    }

    private fun complete(reply: PdfHostReply) {
        val expected = digest.digest().joinToString("") { "%02x".format(it) }
        if (reply.pid == hostPid || reply.uid == hostUid) failure = "PDF 隔离环境不可用"
        else if (reply.total != result.length.toLong() || reply.sequence != sequence || reply.sha != expected)
            failure = "PDF 正文完整性校验失败"
        else ready = true
    }
}
