package com.zhiyu.app.data.duplex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.zhiyu.app.domain.ApiFailure
import org.junit.Assert.*
import org.junit.Test
import rig2d.voice.VoiceStamp

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DuplexAudioOwnershipTest {
    @Test fun failedAcquisitionClosesEveryResourceInReverseOrderExactlyOnce() {
        val events = mutableListOf<Int>()
        val resources = DuplexResourceScope()
        resources.own(AutoCloseable { events += 1 })
        resources.own(AutoCloseable { events += 2; error("release failed") })
        resources.own(AutoCloseable { events += 3 })
        try { resources.close(); fail("release failure must remain explicit") }
        catch (_: IllegalStateException) { }
        resources.close()
        assertEquals(listOf(3, 2, 1), events)
    }

    @Test fun cancellingWarmupRollsBackPreviouslyAcquiredResources() = runTest {
        val events = mutableListOf<String>()
        val task = launch {
            val resources = DuplexResourceScope()
            try {
                resources.own(AutoCloseable { events += "mode" })
                resources.own(AutoCloseable { events += "asr" })
                awaitCancellation()
            } finally { resources.close() }
        }
        runCurrent()
        task.cancel(CancellationException("hang up while warming")); task.join()
        assertEquals(listOf("asr", "mode"), events)
    }

    @Test fun lateStopAndFinallyCannotPauseOrDetachANewerUtterance() {
        val stopped = mutableListOf<String>()
        val owner = DuplexPlaybackOwner<String> { stopped += it }
        val old = VoiceStamp(1, 1); val next = VoiceStamp(1, 2)
        owner.attach(old, "old")
        owner.stop(old)
        owner.detach("old")
        owner.attach(next, "next")
        owner.stop(old); owner.detach("old")
        assertTrue(owner.active(next, "next"))
        assertEquals(listOf("old"), stopped)
        owner.stop(null)
        assertFalse(owner.active(next, "next"))
        assertEquals(listOf("old", "next"), stopped)
    }

    @Test fun shortReadsProduceOnlyImmutableFortyMillisecondFrames() {
        val framer = DuplexPcmFramer()
        val source = ShortArray(1000) { 16384 }
        assertTrue(framer.append(source, 500).isEmpty())
        val first = framer.append(source, 500).single()
        source.fill(0)
        assertEquals(640, first.sampleCount)
        assertEquals(40L, first.durationMillis)
        assertEquals(16000, first.sampleRate)
        assertTrue(first.samplesCopy().all { it == .5f })
        val second = framer.append(ShortArray(280) { -16384 }, 280).single()
        assertEquals(640, second.sampleCount)
        assertTrue(second.samplesCopy().take(360).all { it == .5f })
        assertTrue(second.samplesCopy().drop(360).all { it == -.5f })
        try { framer.append(ShortArray(5), 6); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun platformListenersDoNotThrowWhenStopFailsButCallStillReportsFailure() {
        val health = DuplexAudioHealth()
        health.onFailure { error("platform pause failed") }
        health.fail("voice_duplex_focus_lost")
        try { health.check(); fail("Audio failure cannot be hidden") }
        catch (failure: ApiFailure) { assertEquals("voice_duplex_audio_stop_failed", failure.errorCode) }
        val alreadyFailed = DuplexAudioHealth()
        alreadyFailed.fail("voice_duplex_route_lost")
        alreadyFailed.onFailure { error("platform flush failed") }
        try { alreadyFailed.check(); fail("Late listener failure cannot be hidden") }
        catch (failure: ApiFailure) { assertEquals("voice_duplex_audio_stop_failed", failure.errorCode) }
    }
}
