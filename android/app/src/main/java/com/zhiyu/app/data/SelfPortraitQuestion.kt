package com.zhiyu.app.data

/** A narrow ownership answer; descriptive or compound image questions remain model requests. */
internal object SelfPortraitQuestion {
    private val questions = setOf("这是谁", "这是你吗", "这不是你吗", "你认不认识自己", "你认得自己吗",
        "图里的是谁", "图里是谁", "图上的人是谁", "图片里是谁", "图片里的人是谁", "照片里是谁",
        "照片里的是谁", "照片里的人是谁", "这张照片是谁", "这张照片里是谁", "这是你的照片吗",
        "这是你的自画像吗", "这是你的画像吗")
    fun reply(confirmed: Boolean, text: String, style: String?): String? {
        if (!confirmed || text.length !in 1..48 || text.any { it in "\n\r，,；;。.!！：:“”\"「」" }) return null
        val question = text.trim().trimEnd('?', '？', ' ', '啊', '呀', '呢')
        if (question !in questions) return null
        return when (style) {
            "playful" -> "是我呀，没认出来？"
            "anxious" -> "是我呀，你没认出我吗？"
            "distant", "steady" -> "是我。"
            else -> "是我呀。"
        }
    }
}
