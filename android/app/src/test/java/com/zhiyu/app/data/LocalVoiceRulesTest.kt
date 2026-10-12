package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceRulesTest {
    @Test fun threeShortcutsStayFixedButEveryBundledTimbreCanBeChosen() {
        assertEquals(listOf(10, 33, 0), LocalVoiceRules.choices.map { it.id })
        assertEquals(3, LocalVoiceRules.choices.map { it.name }.distinct().size)
        assertEquals(174, LocalVoiceRules.SPEAKER_COUNT)
        (0 until 174).forEach { assertEquals(it, LocalVoiceRules.voice(it).id) }
        listOf(-1,174,999).forEach { try { LocalVoiceRules.voice(it); fail() } catch (_: IllegalArgumentException) { } }
    }
    @Test fun previousQuietThirdVoiceMigratesButOtherSelectionsAndUnknownIdsDoNotChange() {
        assertEquals(0, LocalVoiceRules.storedVoice(99))
        LocalVoiceRules.choices.forEach { assertEquals(it.id, LocalVoiceRules.storedVoice(it.id)) }
        try { LocalVoiceRules.storedVoice(999); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun supersededThirdVoiceKeepsTheSelectedSlotWithTheClearerVoice() {
        assertEquals(0, LocalVoiceRules.storedVoice(2))
        assertEquals(2, LocalVoiceRules.voice(2).id)
    }
    @Test fun everySpokenChunkKeepsCompleteTextAndUnicodePairsIncludingVeryLongAnswers() {
        val text = ("我想陪你慢慢聊，今天的天气真好。🙂𠀀\n".repeat(300)) + "结尾不会丢"
        val parts = LocalVoiceRules.speechChunks(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.size > 50)
        assertTrue(parts.all { it.length in 1..120 })
        assertTrue(parts.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
    }
    @Test fun boundarySurrogateIsNeverCutInHalfWithoutAnyPunctuation() {
        val text = "a".repeat(119) + "🙂" + "字".repeat(300)
        assertEquals(text, LocalVoiceRules.speechChunks(text).joinToString(""))
        assertEquals(119, LocalVoiceRules.speechChunks(text).first().length)
    }
    @Test fun invalidUnicodeOrEmptyTextDoNotReachNativeTts() {
        listOf("", "  ", "bad\uD800", "字".repeat(LocalVoiceRules.MAX_SPOKEN_CHARS + 1)).forEach {
            try { LocalVoiceRules.speechChunks(it); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun resamplingReturnsExpectedDurationAndNeverMutatesOrAliasesOriginal() {
        val input = FloatArray(22050) { 0.25f }
        val result = LocalVoiceRules.resample(input, 22050)
        assertEquals(16000, result.size)
        assertTrue(result.all { it == 0.25f })
        result[0] = 0f
        assertEquals(0.25f, input[0])
        val identity = LocalVoiceRules.resample(input, 22050, 22050)
        identity[0] = -1f
        assertEquals(0.25f, input[0])
    }
    @Test fun malformedSamplesRatesAndUnboundedOutputFailClosed() {
        listOf(floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(1.1f), floatArrayOf()).forEach {
            try { LocalVoiceRules.checkAudio(it, 22050); fail() } catch (_: IllegalArgumentException) { }
        }
        try { LocalVoiceRules.checkAudio(floatArrayOf(0f), 0); fail() } catch (_: IllegalArgumentException) { }
        try { LocalVoiceRules.checkAudio(FloatArray(8000 * 90 + 1), 8000); fail() } catch (_: IllegalArgumentException) { }
    }
}
