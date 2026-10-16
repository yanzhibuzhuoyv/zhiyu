package rig2d.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Portable coordination core, not a device microphone/AEC/latency acceptance claim. */
class DuplexCallSession(
    private val scope: CoroutineScope,
    private val factory: DuplexPortsFactory,
    private val config: DuplexConfig = DuplexConfig(),
    private val onEvent: (VoiceEvent) -> Unit = {},
    private val onMouth: (VoiceStamp, Float) -> Unit = { _, _ -> },
) {
    private val rules = config.checked()
    private val lifecycle = Mutex()
    private val generation = AtomicLong(0)
    private val current = AtomicReference<Run?>()

    /** Caller must keep ports/observers fast; native adapters own their cooperative cancellation. */
    suspend fun start(): Long = lifecycle.withLock {
        scope.coroutineContext.ensureActive()
        val previous = current.get()
        if (previous?.running() == true) return@withLock previous.session
        previous?.job?.join()
        val session = generation.incrementAndGet()
        check(session > 0) { "Voice session epoch exhausted" }
        val ports = try { factory.open() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            onEvent(VoiceEvent.Fault(session, VoiceFault.CAPTURE))
            throw IllegalStateException("Voice ports could not be opened")
        }
        val run = Run(session, ports)
        try {
            // Opening may suspend while either the owner or caller is cancelled.
            scope.coroutineContext.ensureActive()
            currentCoroutineContext().ensureActive()
            run.job = scope.launch(start = CoroutineStart.LAZY) { run.execute() }
            current.set(run)
            // A lazy coroutine cancelled before dispatch never enters execute's try/finally.
            // Completion owns the same exactly-once fallback as the normally entered body.
            run.job.invokeOnCompletion { run.release() }
            run.job.start()
        } catch (cancelled: CancellationException) {
            run.release()
            throw cancelled
        } catch (failure: Exception) {
            run.release()
            throw failure
        }
        session
    }

    suspend fun stop() = lifecycle.withLock {
        val run = current.get() ?: return@withLock
        run.closing.set(true)
        run.signalStop()
        run.job.cancel()
        withContext(NonCancellable) { run.job.join() }
    }

    private data class Reply(val stamp: VoiceStamp, val job: Job)

    private inner class Run(val session: Long, val ports: DuplexPorts) {
        lateinit var job: Job
        val closing = AtomicBoolean(false)
        private val releaseFailed = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private var releaseReported = false
        private var utterance = 0L
        @Volatile private var reply: Reply? = null
        private val replyLease = Mutex()

        fun running(): Boolean = current.get() === this && !closing.get() && job.isActive
        private fun replying(stamp: VoiceStamp): Boolean = running() &&
            reply?.let { it.stamp == stamp && it.job.isActive } == true

        suspend fun execute() {
            try {
                event(VoiceEvent.Started(session))
                coroutineScope {
                    val frames = Channel<VoicePcm>(rules.captureQueueCapacity)
                    launch { capture(frames) }
                    consume(this, frames)
                }
            } catch (_: CancellationException) {
                // stop/owner cancellation is intentional; it is not a provider failure.
            } catch (failure: VoiceStageFailure) {
                if (failure.fault == VoiceFault.RELEASE) releaseReported = true
                event(VoiceEvent.Fault(session, failure.fault))
            } catch (_: Exception) {
                event(VoiceEvent.Fault(session, VoiceFault.CAPTURE))
            } finally {
                closing.set(true)
                withContext(NonCancellable) { release() }
            }
        }

        private suspend fun capture(output: Channel<VoicePcm>): Unit = voiceStage(VoiceFault.CAPTURE) {
            var sampleRate: Int? = null
            try {
                ports.capture.frames().collect { frame ->
                    currentCoroutineContext().ensureActive()
                    if (!running()) throw CancellationException("Stale capture session")
                    require(frame.durationMillis in 1..rules.captureFrameMaxMillis.toLong())
                    if (sampleRate == null) sampleRate = frame.sampleRate
                    require(sampleRate == frame.sampleRate) { "Capture rate changed mid session" }
                    if (output.trySend(frame).isFailure) throw VoiceStageFailure(VoiceFault.CAPTURE_OVERFLOW)
                }
                // A live microphone should end only through cancellation, never masquerade as success.
                throw VoiceStageFailure(VoiceFault.CAPTURE)
            } finally { output.close() }
        }

        private suspend fun consume(owner: CoroutineScope, input: Channel<VoicePcm>) {
            val detector = VadEndpointDetector(rules)
            val preRoll = ArrayDeque<VoicePcm>()
            var preRollMillis = 0L
            var recognition: VoiceRecognition? = null
            var stamp = VoiceStamp(session, 0)
            var lastPartial = ""
            try {
                for (frame in input) {
                    currentCoroutineContext().ensureActive()
                    if (!running()) throw CancellationException("Stale recognition session")
                    preRoll.addLast(frame); preRollMillis += frame.durationMillis
                    while (preRoll.size > 1 && preRollMillis - preRoll.first().durationMillis >= rules.preRollMillis) {
                        preRollMillis -= preRoll.removeFirst().durationMillis
                    }
                    val decision = voiceStage(VoiceFault.RECOGNITION) {
                        detector.accept(ports.vad.isSpeech(frame), frame.durationMillis.toInt())
                    }
                    if (decision.started) {
                        interruptReply()
                        stamp = VoiceStamp(session, ++utterance)
                        recognition = voiceStage(VoiceFault.RECOGNITION) { ports.recognizer.open(stamp) }
                        lastPartial = ""
                        event(VoiceEvent.Listening(stamp))
                        for (earlier in preRoll) lastPartial = accept(recognition, earlier, stamp, lastPartial)
                    } else if (recognition != null) {
                        lastPartial = accept(recognition, frame, stamp, lastPartial)
                    }
                    if (decision.ended) {
                        val owned = recognition; recognition = null
                        val text = if (owned == null) "" else finish(owned, decision.accepted)
                        if (releaseFailed.get()) throw VoiceStageFailure(VoiceFault.RELEASE)
                        if (text.isNotBlank()) {
                            event(VoiceEvent.Transcript(stamp, text, true))
                            startReply(owner, stamp, text)
                        }
                        preRoll.clear(); preRollMillis = 0; lastPartial = ""
                    }
                }
            } finally {
                recognition?.let(::closePort)
                input.cancel()
            }
        }

        private suspend fun accept(recognition: VoiceRecognition, frame: VoicePcm,
            stamp: VoiceStamp, previous: String): String {
            val text = voiceStage(VoiceFault.RECOGNITION) {
                recognition.accept(frame).checkedTranscript(rules.maxTranscriptCodePoints)
            }
            currentCoroutineContext().ensureActive()
            if (!running()) throw CancellationException("Stale recognition result")
            if (text != previous && text.isNotBlank()) event(VoiceEvent.Transcript(stamp, text, false))
            return text
        }

        private suspend fun finish(recognition: VoiceRecognition, accepted: Boolean): String {
            val text = try {
                if (accepted) voiceStage(VoiceFault.RECOGNITION) {
                    recognition.finish().checkedTranscript(rules.maxTranscriptCodePoints)
                } else ""
            } finally { closePort(recognition) }
            currentCoroutineContext().ensureActive()
            if (!running()) throw CancellationException("Stale recognition final")
            return text
        }

        private fun startReply(owner: CoroutineScope, stamp: VoiceStamp, text: String) {
            val response = owner.launch(start = CoroutineStart.LAZY) {
                try {
                    // A previous cooperative native cancellation must finish before sharing TTS/player ports.
                    replyLease.withLock {
                        if (!replying(stamp)) throw CancellationException("Stale reply lease")
                        VoiceReplyPipeline(ports, rules).run(text, stamp, { replying(stamp) }) { level ->
                            if (replying(stamp)) onMouth(stamp, level)
                        }
                        if (replying(stamp)) event(VoiceEvent.ReplyComplete(stamp))
                    }
                } catch (_: CancellationException) {
                    // Epoch guards also reject adapters that invoke a callback after this cancellation.
                } catch (failure: VoiceStageFailure) {
                    if (replying(stamp)) {
                        event(VoiceEvent.Fault(session, failure.fault))
                        if (!cancelPorts(stamp)) throw VoiceStageFailure(VoiceFault.RELEASE)
                        onMouth(stamp, 0f)
                    }
                } catch (_: Exception) {
                    if (replying(stamp)) event(VoiceEvent.Fault(session, VoiceFault.GENERATION))
                }
            }
            reply = Reply(stamp, response)
            response.start()
        }

        private fun interruptReply() {
            val previous = reply ?: return
            reply = null // Invalidate before cancellation/stop can synchronously invoke a late callback.
            if (previous.job.isActive) {
                previous.job.cancel()
                if (!cancelPorts(previous.stamp)) throw VoiceStageFailure(VoiceFault.RELEASE)
                if (running()) {
                    onMouth(previous.stamp, 0f)
                    event(VoiceEvent.Interrupted(previous.stamp))
                }
            }
        }

        fun signalStop() {
            val previous = reply
            reply = null
            previous?.job?.cancel()
            if (previous != null) cancelPorts(previous.stamp)
            try { ports.player.stop(null) } catch (_: Exception) { releaseFailed.set(true) }
        }

        private fun cancelPorts(stamp: VoiceStamp): Boolean {
            var success = true
            try { ports.tokens.cancel(stamp) } catch (_: Exception) { success = false }
            try { ports.synthesizer.cancel(stamp) } catch (_: Exception) { success = false }
            try { ports.player.stop(stamp) } catch (_: Exception) { success = false }
            if (!success) releaseFailed.set(true)
            return success
        }

        private fun closePort(port: AutoCloseable) {
            try { port.close() } catch (_: Exception) { releaseFailed.set(true) }
        }

        private fun closeAll() {
            listOf(ports.player, ports.synthesizer, ports.tokens, ports.recognizer, ports.capture).forEach(::closePort)
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            closing.set(true)
            signalStop()
            closeAll()
            try {
                // Terminal callbacks happen before a restart can acquire fresh ports.
                onMouth(VoiceStamp(session, utterance), 0f)
                if (releaseFailed.get() && !releaseReported) onEvent(VoiceEvent.Fault(session, VoiceFault.RELEASE))
                onEvent(VoiceEvent.Stopped(session))
            } finally { current.compareAndSet(this, null) }
        }

        private fun event(value: VoiceEvent) { if (running()) onEvent(value) }
    }
}
