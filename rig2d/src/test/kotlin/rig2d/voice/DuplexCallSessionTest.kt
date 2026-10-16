package rig2d.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuplexCallSessionTest {
    @Test fun captureContinuesDuringPlaybackWithoutFinishButton() = runTest {
        val ports = DuplexFakePorts(); val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        assertTrue(ports.playing)
        val before = ports.reads
        ports.feed(this, false, 20)
        assertEquals(before + 20, ports.reads)
        assertEquals(1, ports.generationCalls)
        assertTrue(ports.events.filterIsInstance<VoiceEvent.Transcript>().any { it.final })
        call.stop()
    }

    @Test fun speechBargesInAndCancelsGenerationSynthesisAndActualPlayback() = runTest {
        val ports = DuplexFakePorts()
        ports.modelFlow = { flow { emit("第一句。"); awaitCancellation() } }
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        assertTrue(ports.playing)
        ports.feed(this, true, 3)
        assertFalse(ports.playing)
        assertTrue(ports.playStops > 0 && ports.generationCancels > 0 && ports.synthCancels > 0)
        assertEquals(1, ports.generationFinally)
        assertEquals(1, ports.events.filterIsInstance<VoiceEvent.Interrupted>().size)
        ports.feed(this, true, 2); ports.feed(this, false, 12)
        assertEquals(2, ports.generationCalls)
        call.stop()
    }

    @Test fun lateMouthCallbackFromInterruptedTurnIsIgnored() = runTest {
        val ports = DuplexFakePorts(); val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        val callback = ports.levelCallbacks.first()
        ports.feed(this, true, 3)
        val count = ports.mouths.size
        callback(1f)
        assertEquals(count, ports.mouths.size)
        assertEquals(0f, ports.mouths.last().second, 0f)
        call.stop()
    }

    @Test fun severalTokenChunksAreSynthesizedAndPlayedInOrderBeforeModelCompletion() = runTest {
        val ports = DuplexFakePorts(); ports.playMillis = 100
        ports.modelFlow = { flow { emit("第一句。"); emit("第二句！"); delay(10_000); emit("最后") } }
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        advanceTimeBy(250); runCurrent()
        assertEquals(listOf("第一句。", "第二句！"), ports.synthesized.map { it.second })
        assertEquals(2, ports.played.size)
        assertEquals(0, ports.generationFinally)
        advanceTimeBy(10_000); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(listOf("第一句。", "第二句！", "最后"), ports.synthesized.map { it.second })
        assertEquals(1, ports.events.filterIsInstance<VoiceEvent.ReplyComplete>().size)
        call.stop()
    }

    @Test fun boundedOutputQueueBackpressuresWithoutDroppingSentences() = runTest {
        val ports = DuplexFakePorts(); ports.playMillis = 1000
        var emitted = 0
        ports.modelFlow = { flow { repeat(30) { emit("第${it}句。"); emitted++ } } }
        val call = ports.session(backgroundScope, DuplexConfig(textQueueCapacity = 1, audioQueueCapacity = 1))
        call.start(); runCurrent(); ports.utterance(this)
        assertTrue("generation must be bounded while playback is blocked", emitted < 30)
        advanceTimeBy(31_000); runCurrent()
        assertEquals(30, ports.played.size)
        assertEquals(30, ports.synthesized.size)
        call.stop()
    }

    @Test fun captureOverflowIsAnExplicitFaultAndReleasesEveryPort() = runTest {
        val ports = DuplexFakePorts(); ports.acceptGate = CompletableDeferred()
        val call = ports.session(backgroundScope, DuplexConfig(captureQueueCapacity = 2))
        call.start(); runCurrent(); ports.feed(this, true, 3)
        repeat(10) { ports.input.trySend(DuplexFakePorts.pcm(false)) }; runCurrent()
        assertTrue(ports.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.CAPTURE_OVERFLOW })
        assertEquals(1, ports.captureClosed)
        assertEquals(1, ports.recognizerClosed)
        assertEquals(1, ports.playClosed)
        call.stop()
        assertEquals(1, ports.captureClosed)
    }

    @Test fun silenceAndEmptyRecognitionNeverCallTheModel() = runTest {
        val ports = DuplexFakePorts(); ports.recognitionText = " "
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.feed(this, false, 30); ports.utterance(this)
        assertEquals(0, ports.generationCalls)
        assertEquals(1, ports.recognitionClosed)
        call.stop()
    }

    @Test fun replyFailureIsExplicitAndCaptureRemainsAvailableForNextUtterance() = runTest {
        val ports = DuplexFakePorts(); ports.synthFailure = true
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        assertTrue(ports.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.SYNTHESIS })
        assertEquals(0, ports.captureClosed)
        ports.synthFailure = false; ports.utterance(this)
        assertTrue(ports.playing)
        assertEquals(2, ports.generationCalls)
        call.stop()
    }

    @Test fun recognitionFailureStopsWithoutLeakingTheExceptionText() = runTest {
        val ports = DuplexFakePorts(); ports.recognitionFailure = true
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.feed(this, true, 3)
        assertTrue(ports.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.RECOGNITION })
        assertFalse(ports.events.toString().contains("NOT_SECRET"))
        assertEquals(1, ports.recognitionClosed)
        assertEquals(1, ports.captureClosed)
        call.stop()
    }

    @Test fun stopRestartUsesFreshEpochAndOldCallbacksCannotDriveNewAvatar() = runTest {
        val old = DuplexFakePorts(); val fresh = DuplexFakePorts(); var opens = 0
        val mouths = mutableListOf<Pair<VoiceStamp, Float>>()
        val call = DuplexCallSession(backgroundScope, { if (opens++ == 0) old.bundle else fresh.bundle },
            onMouth = { stamp, level -> mouths += stamp to level })
        val first = call.start(); runCurrent(); old.utterance(this)
        val callback = old.levelCallbacks.first()
        call.stop(); val second = call.start(); runCurrent()
        assertTrue(second > first)
        val count = mouths.size
        callback(1f); old.input.trySend(DuplexFakePorts.pcm(true)); runCurrent()
        assertEquals(count, mouths.size)
        assertEquals(0, fresh.generationCalls)
        fresh.utterance(this); assertTrue(fresh.playing)
        call.stop(); call.stop()
        assertEquals(1, old.captureClosed)
        assertEquals(1, fresh.captureClosed)
        assertEquals(1, old.synthClosed)
        assertEquals(1, fresh.synthClosed)
    }

    @Test fun mouthOutputIsBoundedAndStartDoesNotAcquireDuplicateResources() = runTest {
        val ports = DuplexFakePorts(); var opens = 0
        val call = DuplexCallSession(backgroundScope, { opens++; ports.bundle }, onMouth = { stamp, level ->
            ports.mouths += stamp to level
        })
        val first = call.start(); assertEquals(first, call.start()); runCurrent(); ports.utterance(this)
        val callback = ports.levelCallbacks.first()
        callback(Float.NaN); callback(-3f); callback(3f)
        assertTrue(ports.mouths.all { it.second.isFinite() && it.second in 0f..1f })
        assertEquals(1, opens)
        call.stop()
        assertEquals(0f, ports.mouths.last().second, 0f)
    }

    @Test fun preRollIncludesTheFirstSpeechFramesBeforeVadStarted() = runTest {
        val ports = DuplexFakePorts(); val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        assertEquals(17, ports.recognitionAccepts)
        call.stop()
    }

    @Test fun cancelledModelCannotEnqueueLateTokensEvenIfTheAdapterFinishesLate() = runTest {
        val ports = DuplexFakePorts()
        ports.modelFlow = {
            object : Flow<String> {
                override suspend fun collect(collector: FlowCollector<String>) {
                    collector.emit("有效句。");
                    try { awaitCancellation() }
                    catch (_: CancellationException) {
                        withContext(NonCancellable) { delay(20); collector.emit("陈旧句。"); }
                    }
                }
            }
        }
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        ports.feed(this, true, 3); advanceTimeBy(30); runCurrent()
        assertFalse(ports.synthesized.any { it.second.contains("陈旧") })
        call.stop()
    }

    @Test fun stopDuringSynthesisCancelsTheTtsAndDoesNotStartPlayback() = runTest {
        val ports = DuplexFakePorts(); ports.synthGate = CompletableDeferred()
        val call = ports.session(backgroundScope)
        call.start(); runCurrent(); ports.utterance(this)
        assertEquals(1, ports.synthesized.size)
        call.stop(); ports.synthGate!!.complete(Unit); runCurrent()
        assertEquals(0, ports.played.size)
        assertTrue(ports.synthCancels > 0)
        assertEquals(1, ports.synthClosed)
    }

    @Test fun cancelledOwnerScopeReleasesCapturePlaybackAndNativePorts() = runTest {
        val ports = DuplexFakePorts()
        val owner = SupervisorJob()
        val ownerScope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val call = ports.session(ownerScope)
        call.start(); runCurrent(); ports.utterance(this)
        owner.cancel(); runCurrent()
        assertEquals(1, ports.captureClosed)
        assertEquals(1, ports.recognizerClosed)
        assertEquals(1, ports.tokensClosed)
        assertEquals(1, ports.synthClosed)
        assertEquals(1, ports.playClosed)
        call.stop()
    }

    @Test fun modelAndPlaybackFailureHaveDistinctTypedFaults() = runTest {
        val modelPorts = DuplexFakePorts()
        modelPorts.modelFlow = { flow { error("NOT_SECRET: model error") } }
        val modelCall = modelPorts.session(backgroundScope)
        modelCall.start(); runCurrent(); modelPorts.utterance(this)
        assertTrue(modelPorts.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.GENERATION })
        modelCall.stop()
        val playerPorts = DuplexFakePorts(); playerPorts.playFailure = true
        val playerCall = playerPorts.session(backgroundScope)
        playerCall.start(); runCurrent(); playerPorts.utterance(this)
        assertTrue(playerPorts.events.any { it is VoiceEvent.Fault && it.fault == VoiceFault.PLAYBACK })
        playerCall.stop()
    }
}
