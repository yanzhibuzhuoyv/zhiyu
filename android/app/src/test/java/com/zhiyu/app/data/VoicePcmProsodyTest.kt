package com.zhiyu.app.data

import com.zhiyu.app.data.voicemessages.VoicePcmProsody
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoicePcmProsodyTest {
    private val rate = 16000
    private fun tone(amplitude: Float = .5f) = FloatArray(rate) { (sin(2 * PI * 220 * it / rate) * amplitude).toFloat() }
    private fun crossings(samples: FloatArray, start: Int, end: Int): Int =
        (start + 1 until end).count { samples[it - 1] <= 0f && samples[it] > 0f }

    @Test fun questionsHaveASmallRisingTailAndStatementsHaveAFallingTail() {
        val source = tone()
        val question = VoicePcmProsody.apply(source, rate, "你还在吗？")
        val statement = VoicePcmProsody.apply(source, rate, "我就在这儿。")
        assertTrue("Question cadence needs a real PCM contour", !source.contentEquals(question))
        val questionTail = crossings(question, question.size * 3 / 4, question.size)
        val statementTail = crossings(statement, statement.size * 3 / 4, statement.size)
        assertTrue("Questions and statements must have different audible contours", questionTail > statementTail)
        assertTrue(question.size in source.size * 97 / 100..source.size * 103 / 100)
        assertTrue(statement.size in source.size * 97 / 100..source.size * 103 / 100)
    }

    @Test fun outputRemainsFiniteBoundedAndDoesNotOverwriteItsSource() {
        val source = tone(.95f)
        val original = source.copyOf()
        val result = VoicePcmProsody.apply(source, rate, "等等，我很想你！")
        assertArrayEquals(original, source, 0f)
        assertNotSame(source, result)
        assertTrue(result.all { it.isFinite() && abs(it) <= 1f })
        val energy = sqrt(result.sumOf { it.toDouble() * it } / result.size)
        assertTrue("Prosody must not turn speech into silence or sudden loudness", energy in .5..0.8)
    }

    @Test fun samePhraseAndVoiceStayDeterministicWithoutRandomTimbreNoise() {
        val source = tone()
        val first = VoicePcmProsody.apply(source, rate, "你今天过得好吗？")
        val second = VoicePcmProsody.apply(source, rate, "你今天过得好吗？")
        assertArrayEquals(first, second, 0f)
        assertFalse("A deterministic contour must still modulate the speech", source.contentEquals(first))
    }

    @Test fun silenceIsPreservedWithoutManufacturedNoise() {
        val result = VoicePcmProsody.apply(FloatArray(rate), rate, "嗯。")
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it == 0f })
    }

    @Test fun malformedAudioAndBlankOrInvalidTextFailBeforeAnyProcessing() {
        listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(1.1f)).forEach { source ->
            try { VoicePcmProsody.apply(source, rate, "嗯。"); fail("Malformed audio accepted") }
            catch (_: IllegalArgumentException) { }
        }
        listOf("", " ", "坏\uD800", "字".repeat(121)).forEach { text ->
            try { VoicePcmProsody.apply(tone(), rate, text); fail("Malformed text accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { VoicePcmProsody.apply(tone(), 0, "嗯。"); fail("Invalid sample rate accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationIsObservedBeforeTheDerivativeIsReturned() {
        try { VoicePcmProsody.apply(tone(), rate, "等一下。") { false }; fail("Cancelled audio processing completed") }
        catch (_: CancellationException) { }
    }
}
