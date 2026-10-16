package com.zhiyu.app.data.voicemessages

object VoiceMessageOrigins {
    private val idPattern = Regex("[a-f0-9]{32}")
    fun user(id: String): String { require(idPattern.matches(id)); return "voice_user:$id" }
    fun reply(id: String): String { require(idPattern.matches(id)); return "voice_reply:$id" }
    fun clipId(origin: String): String? {
        val id = when {
            origin.startsWith("voice_user:") -> origin.removePrefix("voice_user:")
            origin.startsWith("voice_reply:") -> origin.removePrefix("voice_reply:")
            else -> return null
        }
        return id.takeIf(idPattern::matches)
    }
    fun isVoice(origin: String): Boolean = clipId(origin) != null
}
