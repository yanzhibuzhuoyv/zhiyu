package rig2d.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceRulesTest {
    @Test fun pcmIsCopiedAndRmsIsFinite() {
        val source = floatArrayOf(0.5f, -0.5f)
        val pcm = VoicePcm(source, 16000)
        source[0] = 0f
        val copy = pcm.samplesCopy(); copy[1] = 0f
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), pcm.samplesCopy(), 0f)
        assertEquals(0.5f, pcm.rms(), 0.0001f)
    }

    @Test fun pcmRejectsInvalidSamplesRateAndUnboundedChunks() {
        for (values in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(1.1f))) {
            assertThrows(IllegalArgumentException::class.java) { VoicePcm(values, 16000) }
        }
        assertThrows(IllegalArgumentException::class.java) { VoicePcm(floatArrayOf(0f), 0) }
        assertThrows(IllegalArgumentException::class.java) { VoicePcm(FloatArray(16000 * 31), 16000) }
    }

    @Test fun configRejectsUnboundedQueuesAndInconsistentVadLimits() {
        assertEquals(DuplexConfig(), DuplexConfig().checked())
        listOf(DuplexConfig(captureQueueCapacity = 0), DuplexConfig(audioQueueCapacity = Int.MAX_VALUE),
            DuplexConfig(textQueueCapacity = -1), DuplexConfig(startSpeechMillis = 1000, minSpeechMillis = 60),
            DuplexConfig(maxUtteranceMillis = 1), DuplexConfig(chunkCodePoints = 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.checked() }
        }
    }

    @Test fun energyVadIsReplaceableAndRejectsInvalidThresholds() {
        val vad = EnergyVoiceActivityDetector(0.03f)
        assertFalse(vad.isSpeech(VoicePcm(FloatArray(320) { 0.01f }, 16000)))
        assertTrue(vad.isSpeech(VoicePcm(FloatArray(320) { 0.1f }, 16000)))
        assertThrows(IllegalArgumentException::class.java) { EnergyVoiceActivityDetector(Float.NaN) }
    }

    @Test fun vadStartsAfterSustainedSpeechAndEndsAfterSustainedSilence() {
        val vad = VadEndpointDetector()
        repeat(2) { assertFalse(vad.accept(true, 20).started) }
        assertTrue(vad.accept(true, 20).started)
        repeat(2) { vad.accept(true, 20) }
        repeat(11) { assertFalse(vad.accept(false, 20).ended) }
        assertEquals(VadDecision(ended = true, accepted = true), vad.accept(false, 20))
    }

    @Test fun shortNoiseDoesNotCreateTranscriptAndPausesDoNotEndSpeech() {
        val vad = VadEndpointDetector()
        assertFalse(vad.accept(true, 20).started)
        repeat(20) { assertFalse(vad.accept(false, 20).ended) }
        repeat(3) { vad.accept(true, 20) }
        repeat(5) { assertFalse(vad.accept(false, 20).ended) }
        repeat(2) { vad.accept(true, 20) }
        repeat(11) { assertFalse(vad.accept(false, 20).ended) }
        assertTrue(vad.accept(false, 20).accepted)
    }

    @Test fun maximumUtteranceAndResetAreBounded() {
        val vad = VadEndpointDetector(DuplexConfig(maxUtteranceMillis = 200))
        repeat(9) { assertFalse(vad.accept(true, 20).ended) }
        assertTrue(vad.accept(true, 20).ended)
        vad.reset()
        assertFalse(vad.accept(false, 20).ended)
        assertThrows(IllegalArgumentException::class.java) { vad.accept(true, 0) }
    }

    @Test fun chunkerEmitsSeveralSentencesBeforeTheModelFinishes() {
        val chunks = SentenceChunker()
        assertEquals(emptyList<String>(), chunks.accept("今天"))
        assertEquals(listOf("今天很好。", "一起走走！"), chunks.accept("很好。一起走走！还有"))
        assertEquals(listOf("还有"), chunks.finish())
        assertEquals(emptyList<String>(), chunks.finish())
    }

    @Test fun chunkBoundsNeverCutEmojiAndCrossTokenSurrogatesAreJoined() {
        val chunks = SentenceChunker(4, 100)
        val result = chunks.accept("abc😀xyz。") + chunks.finish()
        assertEquals(listOf("abc😀", "xyz。"), result)
        assertTrue(result.all { it.codePointCount(0, it.length) <= 4 })
        val split = SentenceChunker(2, 100)
        assertEquals(emptyList<String>(), split.accept("\uD83D"))
        assertEquals(listOf("😀。"), split.accept("\uDE00。"))
    }

    @Test fun whitespaceNeverSpeaksAndReplyLimitsRejectUnboundedOutput() {
        val chunks = SentenceChunker(4, 5)
        assertEquals(emptyList<String>(), chunks.accept(" \n"))
        assertThrows(IllegalArgumentException::class.java) { chunks.accept("abcdef") }
        val invalid = SentenceChunker()
        invalid.accept("\uD83D")
        assertThrows(IllegalArgumentException::class.java) { invalid.finish() }
    }
}
