package rig2d.audit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rig2d.voice.DuplexCallSession
import rig2d.voice.DuplexFakePorts
import rig2d.voice.VoiceEvent
import rig2d.voice.VoiceFault
import rig2d.voice.VoicePlayer
import rig2d.voice.VoiceStamp

/** Resource ownership must survive cancellation before the lazy coroutine enters its try/finally. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionLifecycleAuditTest {
    @Test fun stopImmediatelyAfterStartClosesPortsEvenBeforeCaptureWasScheduled() = runTest {
        val ports = DuplexFakePorts()
        val call = ports.session(backgroundScope)
        call.start()
        call.stop()
        runCurrent()
        assertEveryPortReleased(ports)
        assertEquals(0, ports.reads)
    }

    @Test fun ownerCancellationWhileOpeningCannotLeakTheReturnedPorts() = runTest {
        val ports = DuplexFakePorts()
        val owner = SupervisorJob()
        val ownerScope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val call = DuplexCallSession(ownerScope, {
            owner.cancel()
            ports.bundle
        })
        runCatching { call.start() }
        runCurrent()
        call.stop()
        assertEveryPortReleased(ports)
        assertEquals(0, ports.reads)
    }

    @Test fun failedHardwareStopDuringBargeInTerminatesTheSessionImmediately() = runTest {
        val ports = DuplexFakePorts()
        val broken = object : VoicePlayer by ports.bundle.player {
            override fun stop(stamp: VoiceStamp?) {
                if (stamp != null) error("NOT_SECRET: hardware flush fixture failed")
                ports.bundle.player.stop(stamp)
            }
        }
        val call = DuplexCallSession(backgroundScope, { ports.bundle.copy(player = broken) },
            onEvent = ports.events::add)
        call.start(); runCurrent(); ports.utterance(this)
        assertTrue(ports.playing)
        ports.feed(this, true, 3)
        assertTrue("flush failure must be explicit before another utterance finishes",
            ports.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.RELEASE })
        assertEveryPortReleased(ports)
        assertEquals(1, ports.generationCalls)
        call.stop()
        assertEveryPortReleased(ports)
    }

    private fun assertEveryPortReleased(ports: DuplexFakePorts) {
        assertEquals("capture must close once", 1, ports.captureClosed)
        assertEquals("recognizer must close once", 1, ports.recognizerClosed)
        assertEquals("token provider must close once", 1, ports.tokensClosed)
        assertEquals("synthesizer must close once", 1, ports.synthClosed)
        assertEquals("player must close once", 1, ports.playClosed)
    }
}
