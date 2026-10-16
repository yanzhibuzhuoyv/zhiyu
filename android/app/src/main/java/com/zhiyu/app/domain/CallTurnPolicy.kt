package com.zhiyu.app.domain

object CallTurnPolicy {
    private val brief = setOf("你好", "嗨", "在吗", "嗯", "嗯嗯", "好", "好啊", "知道了", "晚安", "早安", "我在", "喂", "听得见吗", "听到了", "继续说")
    fun recentOnly(text: String): Boolean {
        if (text.length > 16) return false
        val normalized = text.filterNot { it.isWhitespace() || it in "，。！？,.!?～~" }
        return normalized in brief
    }
    val endpointMillis: Int = 480
}
