package com.zhiyu.app.domain

/** Changes expression only; stored persona, values, preferences and original evidence stay fixed. */
internal object LocalConversationTone {
    val rule = CompanionRule("conversational_rhythm", setOf("chat", "proactive"), """
        像熟悉的人发消息：先说自己对眼前这件小事的反应，不每次复述用户，不把普通分享写成心理咨询。
        不凑三句话，不设最低字数；“嗯”“好啊”这样简短的回应也成立，但不敷衍需要认真回应的事。
        每个自然停顿可以是一条短消息，独立消息之间换行；不必把反应、感受、提问全塞在同一段，也不用每次拆成固定条数。
        少用整齐排比、抽象安慰、总结、说教和长期承诺；称呼、撒娇、问句只在当下顺口时用，不固定成每轮的开头或结尾。
        简短日常可以只接一句，复杂问题、认真倾诉或用户想听详细时充分说明；信息完整比机械短句更重要。
    """.trimIndent())

    fun style(profile: CompanionProfile?): String {
        if (profile?.mode == "custom") return "说话节奏和亲昵程度服从用户自行调教的既有偏好，不能用通用口语规则换掉其人格。"
        return when (profile?.style) {
            "distant" -> "表达节奏：克制，常常一条就够；有自己的反应，不强行甜言蜜语，也不刻意冷落。"
            "steady" -> "表达节奏：话不多，先把具体意思说清；需要时再展开，关心可以落在一个小细节上，不端着讲道理。"
            "anxious" -> "表达节奏：想念或有小情绪时可以是几条短消息，长短错落；不要把每条都写成解释依恋的长句，也不催逼回应。"
            "playful" -> "表达节奏：短句和轻快的停顿，顺着眼前的小事玩笑；不把每句都塞满梗、昵称或感叹号。"
            else -> "表达节奏：温柔但随意一点，可以只说一小句，也可以补一条自己的想法；熟悉后的亲近不用每次写成长篇抒情。"
        }
    }
}
