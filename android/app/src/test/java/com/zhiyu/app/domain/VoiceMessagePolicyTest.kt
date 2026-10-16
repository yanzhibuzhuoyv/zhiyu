package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class VoiceMessagePolicyTest {
    @Test fun recordingKeepsIndependentCopiesAndHasAnExactSixtySecondBound() {
        val buffer = VoiceRecordingBuffer(16000, 1)
        val input = FloatArray(8000) { .1f }
        assertTrue(buffer.append(input)); input[0] = .8f
        assertFalse(buffer.append(FloatArray(8000) { .2f }))
        assertEquals(16000, buffer.samples().size)
        assertEquals(.1f, buffer.samples()[0], 0f)
        val exported = buffer.samples(); exported[0] = .9f
        assertEquals(.1f, buffer.samples()[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { buffer.append(floatArrayOf(.1f)) }
    }
    @Test fun invalidPcmCannotBeRecordedAndClearingReleasesTheOriginal() {
        val buffer = VoiceRecordingBuffer()
        assertThrows(IllegalArgumentException::class.java) { buffer.append(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { buffer.append(floatArrayOf(2f)) }
        buffer.append(floatArrayOf(.1f)); buffer.clear()
        assertTrue(buffer.samples().isEmpty())
    }
    @Test fun explicitVoiceRequestAndReceivedVoiceHaveARealVoiceReply() {
        assertTrue(VoiceMessagePolicy.reply("给我发段语音吧", false, "distant", "a"))
        assertTrue(VoiceMessagePolicy.reply("今天散步啦", true, "gentle", "b"))
    }
    @Test fun explicitTextPreferenceWinsOverVoiceInputAndBlankAnswerIsNotInvented() {
        assertFalse(VoiceMessagePolicy.reply("不要发语音，发文字就好", true, "anxious", "c"))
    }
    @Test fun personalitySelectionIsStableAndReservedVoicesAreLessFrequent() {
        val ids = (1..1000).map(Int::toString)
        val attached = ids.count { VoiceMessagePolicy.reply("今天散步啦", false, "anxious", it) }
        val distant = ids.count { VoiceMessagePolicy.reply("今天散步啦", false, "distant", it) }
        assertTrue(attached > distant); assertTrue(attached in 100..600)
        assertTrue(distant in 1..100)
        assertEquals(VoiceMessagePolicy.reply("好", false, "warm", "same"), VoiceMessagePolicy.reply("好", false, "warm", "same"))
    }
}
