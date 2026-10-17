package com.zhiyu.app.data

import kotlinx.coroutines.*
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.ApiFailure
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Same capture coordinator as hardware, with delayed model and synthetic PCM; no microphone. */
class VoiceCaptureStartupNativeTest {
    private class Input(count: Int = 2, framesPerChunk: Int = 1600) : VoiceCaptureInput {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        private val stopped = AtomicBoolean()
        private val chunks = ConcurrentLinkedQueue((0 until count).map { index ->
            FloatArray(framesPerChunk) { if (index % 2 == 0) .25f else -.5f }
        })
        override fun start() { started.complete(Unit) }
        override fun read(maximumFrames: Int): FloatArray {
            if (stopped.get()) return FloatArray(0)
            return chunks.poll()?.also { reads.incrementAndGet() } ?: FloatArray(0)
        }
        override fun stop() { stopped.set(true) }
        override fun close() { stopped.set(true); closed.complete(Unit) }
    }
    private class Recognition : VoiceCaptureRecognition {
        val received = mutableListOf<FloatArray>()
        override fun accept(samples: FloatArray): String { received.add(samples.copyOf()); return "分段${received.size}" }
        override fun endpoint() = false
        override fun finish() = "完整首句"
        override fun close() = Unit
    }
    private suspend fun happened(signal: CompletableDeferred<Unit>) = withTimeoutOrNull(1_000) { signal.await(); true } == true

    @Test fun coldModelInitializationDoesNotDelayTheActualCaptureStartedSignal(): Unit = runBlocking {
        val input = Input(); val microphone = LocalVoiceMicrophone { input }
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); Recognition() }, {}, {}, autoEndpoint = false) }
        try {
            withTimeout(5_000) { loading.await() }
            assertTrue("Capture must start while ASR is still loading", happened(input.started))
        } finally { release.complete(Unit); microphone.finishTurn(); running.cancelAndJoin(); scope.cancel() }
    }

    @Test fun firstPcmIsBufferedBeforeTheModelIsReadyAndReplayedInOrderAfterFinish(): Unit = runBlocking {
        val input = Input(); val microphone = LocalVoiceMicrophone { input }; val recognition = Recognition()
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val captured = CompletableDeferred<Unit>()
        val count = AtomicInteger(); val partials = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); recognition }, {},
            { partials.add(it) }, autoEndpoint = false, onSamples = { if (count.incrementAndGet() == 2) captured.complete(Unit) }) }
        try {
            withTimeout(5_000) { loading.await() }
            assertTrue("The first utterance must already exist as PCM during model load", happened(captured))
            microphone.finishTurn(); release.complete(Unit)
            assertEquals("完整首句", withTimeout(5_000) { running.await() })
            assertEquals(2, recognition.received.size)
            assertEquals(.25f, recognition.received[0].first(), 0f)
            assertEquals(-.5f, recognition.received[1].first(), 0f)
            assertEquals(listOf("分段1", "分段2"), partials)
            assertTrue(input.closed.isCompleted)
        } finally { release.complete(Unit); running.cancelAndJoin(); scope.cancel() }
    }

    @Test fun cancellationDuringColdLoadingReleasesTheAlreadyOpenedInput(): Unit = runBlocking {
        val input = Input(); val microphone = LocalVoiceMicrophone { input }
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); Recognition() }, {}, {}, autoEndpoint = false) }
        try {
            withTimeout(5_000) { loading.await() }
            running.cancelAndJoin()
            assertTrue("Cold preparation cancellation must release capture/effect ownership", input.closed.isCompleted)
        } finally { release.complete(Unit); running.cancelAndJoin(); scope.cancel() }
    }

    @Test fun shortHardwareReadsMustNotExhaustTheEightSecondPrebufferAfterOnlyOneSecond(): Unit = runBlocking {
        val input = Input(count = 101, framesPerChunk = 160)
        val microphone = LocalVoiceMicrophone { input }; val recognition = Recognition()
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val captured = CompletableDeferred<Unit>()
        val frames = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); recognition }, {}, {},
            autoEndpoint = false, onSamples = { if (frames.addAndGet(it.size) == 16160) captured.complete(Unit) }) }
        try {
            withTimeout(5_000) { loading.await() }
            assertTrue("101 short reads contain only 1.01 seconds of PCM, below the eight-second frame budget", happened(captured))
            microphone.finishTurn(); release.complete(Unit)
            assertEquals("完整首句", withTimeout(5_000) { running.await() })
            val actual = recognition.received.flatMap { it.toList() }
            assertEquals("Finishing must also flush the final incomplete 1600-frame block", 16160, actual.size)
            actual.forEachIndexed { index, value -> assertEquals(if ((index / 160) % 2 == 0) .25f else -.5f, value, 0f) }
            assertTrue(input.closed.isCompleted)
        } finally { release.complete(Unit); running.cancelAndJoin(); scope.cancel() }
    }

    @Test fun aBlockedModelCannotGrowThePcmPrebufferWithoutBoundOrDiscardItsPrefix(): Unit = runBlocking {
        val input = Input(200); val microphone = LocalVoiceMicrophone { input }
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); Recognition() }, {}, {},
            maximumSeconds = 60, autoEndpoint = false) }
        try {
            withTimeout(5_000) { loading.await() }
            assertTrue("An exhausted bounded prebuffer must explicitly stop capture", happened(input.closed))
            assertTrue("Eight seconds plus one read is the maximum retained startup PCM", input.reads.get() <= 81)
            val failure = runCatching { withTimeout(5_000) { running.await() } }.exceptionOrNull()
            assertTrue("Overflow must be an explicit capture failure rather than an await timeout", failure is ApiFailure)
            assertEquals("voice_prebuffer_full", (failure as ApiFailure).errorCode)
        } finally { release.complete(Unit); running.cancelAndJoin(); scope.cancel() }
    }

    @Test fun theBundledRecognizerReceivesAWholeGeneratedUtteranceCapturedDuringColdPreparation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pcm = LocalVoiceEngine(context).use {
            val audio = it.synthesize("你好，今天的天气真好。", 10)
            LocalVoiceRules.resample(audio.samples, audio.sampleRate)
        }
        val chunks = ConcurrentLinkedQueue((pcm.indices step 1600).map { start -> pcm.copyOfRange(start, minOf(start + 1600, pcm.size)) })
        val input = object : VoiceCaptureInput {
            override fun start() = Unit
            override fun read(maximumFrames: Int) = chunks.poll() ?: FloatArray(0)
            override fun stop() = Unit
            override fun close() = Unit
        }
        val microphone = LocalVoiceMicrophone { input }
        val loading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val captured = CompletableDeferred<Unit>()
        val frames = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        LocalVoiceEngine(context).use { engine ->
            val running = scope.async { microphone.capture({ loading.complete(Unit); release.await(); engine.openRecognition() }, {}, {},
                autoEndpoint = false, onSamples = { if (frames.addAndGet(it.size) == pcm.size) captured.complete(Unit) }) }
            try {
                withTimeout(5_000) { loading.await() }
                assertTrue("The complete known speech must be captured before opening the ASR model", happened(captured))
                microphone.finishTurn(); release.complete(Unit)
                val result = withTimeout(30_000) { running.await() }
                assertTrue("Bundled ASR must retain recognizable content: $result", result.contains("你好") || result.contains("天气"))
                assertEquals(pcm.size, frames.get())
            } finally { release.complete(Unit); running.cancelAndJoin(); scope.cancel() }
        }
    }
}
