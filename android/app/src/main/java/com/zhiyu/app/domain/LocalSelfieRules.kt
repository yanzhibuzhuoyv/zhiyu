package com.zhiyu.app.domain

data class LocalSelfieTicket(val scope: String, val lane: Int, val generation: Int)

/** Explicit selfie opportunities use a real persisted daily scene, never a synthetic scene. */
object LocalSelfieRules {
    const val SIZE = 3
    fun requested(text: String): Boolean = text.take(10_000).lowercase()
        .split(Regex("[，。！？!?；;\\r\\n]")).any { requestedClause(it.replace(Regex("\\s+"), "")) }

    private fun requestedClause(value: String): Boolean {
        if (Regex("(?:不要|别|不用|不想|不需要).{0,16}(?:自拍|你的照片|你的近照|selfie)").containsMatchIn(value)) return false
        if (Regex("(?:教我|教教我|怎么自拍|如何自拍|怎样自拍|自拍技巧|自拍教程|帮我看|帮我看看)").containsMatchIn(value)) return false
        if (Regex("(?:给你(?:发|看)|发给你|发你|我的|这是我|我(?:想|要|准备|打算|刚|已经|正在)?拍)[^，。！？!?]{0,12}(?:自拍|照片|近照)")
                .containsMatchIn(value)) return false
        // Recipient, companion ownership, or a direct imperative is required; a generic 想/看/给 is insufficient.
        val recipient = "(?:给我(?:发|看|看看)?|发给我|发我)(?:一|两|几)?(?:张|个)?(?:你(?:的|今天的|现在的|最新的|最近的))?(?:自拍|近照)"
        val companion = "(?:(?:我)?(?:想|想要)?(?:看|看看)|(?:请)?发(?:一张|张)?)(?:你(?:的|今天的|现在的|最新的|最近的))(?:自拍|近照|照片)"
        val imperative = "^(?:请|能|可以|能不能|能否)?(?:给我)?(?:来|发|发来)(?:一|两|几)?(?:张|个)?(?:你的)?(?:自拍|近照)(?:吧|呀|嘛|吗|呢|~|～)*$"
        val standalone = "^(?:你的(?:自拍|照片|近照)|自拍)(?:呢|吧|呀|嘛|吗|~|～)*$"
        val english = "(?:send|show|give)me(?:a|one|your)?selfie|(?:iwant|canihave)(?:a|your)?selfie"
        return listOf(recipient, companion, imperative, standalone, english).any { Regex(it).containsMatchIn(value) }
    }
    fun tags(ticket: LocalSelfieTicket): List<String> = check(ticket).let {
        listOf("selfie_scope_${it.scope.take(24)}", "selfie_${it.lane}_${it.generation}")
    }
    fun check(ticket: LocalSelfieTicket): LocalSelfieTicket {
        require(Regex("[a-f0-9]{64}").matches(ticket.scope) && ticket.lane in 0 until SIZE &&
            ticket.generation in 0..1_000_000) { "自拍库存位置无效" }
        return ticket
    }
}
