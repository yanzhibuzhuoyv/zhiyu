package com.zhiyu.app.data

import com.zhiyu.app.data.voicemessages.VoiceMessageOrigins
import org.junit.Assert.*
import org.junit.Test

class VoiceMessageOriginsTest {
    private val id = "a".repeat(32)
    @Test fun voiceOriginsHaveOneExactClipPointerAndNeverReuseCallOrProactiveTags() {
        assertEquals("voice_user:$id", VoiceMessageOrigins.user(id))
        assertEquals("voice_reply:$id", VoiceMessageOrigins.reply(id))
        assertEquals(id, VoiceMessageOrigins.clipId("voice_user:$id"))
        assertEquals(id, VoiceMessageOrigins.clipId("voice_reply:$id"))
        assertTrue(VoiceMessageOrigins.isVoice("voice_reply:$id"))
        listOf("reply", "call_reply", "voice_user:", "voice_reply:$id:tail", "voice_reply:${id.uppercase()}",
            "voice_user:../$id", " voice_user:$id").forEach { assertNull(VoiceMessageOrigins.clipId(it)) }
    }
    @Test fun publicBuildersRejectUntrustedPathsOrMalformedClipIds() {
        listOf("", "../clip", "a".repeat(31), "A".repeat(32), "a".repeat(33)).forEach {
            assertThrows(IllegalArgumentException::class.java) { VoiceMessageOrigins.user(it) }
            assertThrows(IllegalArgumentException::class.java) { VoiceMessageOrigins.reply(it) }
        }
    }
}
