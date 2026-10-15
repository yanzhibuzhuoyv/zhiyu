package com.zhiyu.app.domain

/** Conservative audio-only projection; complete output still goes through the ordinary parsers. */
class CallSpeechFilter {
    private val marker = "[[ZHIYU_"
    private var pending = ""
    private var blocked = false
    private var finished = false
    fun append(delta: String): String {
        check(!finished)
        if (blocked) return ""
        val output = StringBuilder()
        for (character in delta) {
            pending += character
            while (pending.isNotEmpty()) {
                if (pending == marker) { blocked = true; pending = ""; return output.toString() }
                if (marker.startsWith(pending)) break
                val first = pending[0]
                if (Character.isHighSurrogate(first)) {
                    if (pending.length == 1) break
                    require(Character.isLowSurrogate(pending[1])) { "语音正文含无效字符" }
                    output.append(pending.take(2)); pending = pending.drop(2)
                } else {
                    require(!Character.isLowSurrogate(first)) { "语音正文含无效字符" }
                    output.append(first); pending = pending.drop(1)
                }
            }
        }
        return output.toString()
    }
    fun finish(): String {
        check(!finished); finished = true
        require(pending.none(Character::isSurrogate)) { "语音正文含不完整字符" }
        return if (blocked || pending.startsWith("[[")) "" else pending.also { pending = "" }
    }
}
