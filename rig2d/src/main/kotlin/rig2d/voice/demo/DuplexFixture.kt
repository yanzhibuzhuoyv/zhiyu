package rig2d.voice.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import rig2d.voice.*

/** Synthetic host demonstration only: no microphone, ASR model, TTS model, network or device AEC. */
fun main() = runBlocking {
    val fixture = SyntheticDuplexFixture()
    val owner = SupervisorJob(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + owner)
    val secondReply = CompletableDeferred<Unit>()
    var interrupted = 0
    val call = DuplexCallSession(scope, { fixture.ports }, onEvent = { event ->
        val name = event.javaClass.simpleName
        fixture.trace(name)
        if (event is VoiceEvent.Interrupted) interrupted++
        if (event is VoiceEvent.ReplyComplete && event.stamp.utterance == 2L) secondReply.complete(Unit)
        if (event is VoiceEvent.Fault) secondReply.completeExceptionally(IllegalStateException(event.fault.name))
    }, onMouth = { stamp, level -> fixture.trace("mouth", stamp.utterance, level) })
    println("{\"fixture\":\"NOT_REAL_AUDIO_NOT_MODEL_NO_DEVICE_AEC_NO_PRODUCT_LATENCY\"}")
    try {
        call.start()
        withTimeout(5000) { secondReply.await() }
        check(interrupted == 1) { "Fixture must actually interrupt first playback" }
        check(fixture.captureDuringPlayback > 0) { "Fixture must continue capture during playback" }
    } finally {
        call.stop()
        owner.cancel()
    }
    check(fixture.closed == 5) { "Every acquired fixture port must close once" }
    println("{\"result\":\"PASS_SYNTHETIC_COORDINATION_ONLY\",\"interrupted\":$interrupted," +
        "\"capture_frames_during_playback\":${fixture.captureDuringPlayback},\"closed_ports\":${fixture.closed}}")
}

private class SyntheticDuplexFixture {
    private val started = System.nanoTime()
    var playing = false
    var captureDuringPlayback = 0
    var closed = 0
    private var samplesRead = 0

    fun trace(kind: String, utterance: Long? = null, level: Float? = null) {
        val elapsed = (System.nanoTime() - started) / 1_000_000
        println("{\"t_fixture_ms\":$elapsed,\"event\":\"$kind\",\"utterance\":$utterance,\"mouth\":$level}")
    }

    val ports = DuplexPorts(
        capture = object : VoiceCapture {
            override fun frames() = flow {
                suspend fun frames(speech: Boolean, count: Int) {
                    repeat(count) {
                        delay(20)
                        samplesRead++
                        if (playing) captureDuringPlayback++
                        emit(VoicePcm(FloatArray(320) { if (speech) 0.1f else 0f }, 16000))
                    }
                }
                frames(true, 5); frames(false, 20)
                frames(true, 5); frames(false, 12)
                while (currentCoroutineContext().isActive) frames(false, 1)
            }
            override fun close() { closed++; trace("capture_closed") }
        },
        recognizer = object : VoiceRecognizer {
            override suspend fun open(stamp: VoiceStamp) = object : VoiceRecognition {
                override suspend fun accept(frame: VoicePcm) = "NOT_REAL_TRANSCRIPT_${stamp.utterance}"
                override suspend fun finish() = "NOT_REAL_TRANSCRIPT_${stamp.utterance}"
                override fun close() { trace("recognition_closed", stamp.utterance) }
            }
            override fun close() { closed++; trace("recognizer_closed") }
        },
        tokens = object : VoiceTokenSource {
            override fun tokens(transcript: String, stamp: VoiceStamp) = flow {
                emit("合成夹具第一句。"); delay(80); emit("合成夹具第二句！")
            }
            override fun cancel(stamp: VoiceStamp) { trace("model_cancel", stamp.utterance) }
            override fun close() { closed++; trace("model_closed") }
        },
        synthesizer = object : VoiceSynthesizer {
            override suspend fun synthesize(text: String, stamp: VoiceStamp, active: () -> Boolean): VoicePcm {
                trace("tts_chunk_start", stamp.utterance)
                delay(40)
                if (!active()) throw CancellationException("Fixture reply cancelled")
                return VoicePcm(FloatArray(320) { 0.1f }, 16000)
            }
            override fun cancel(stamp: VoiceStamp) { trace("tts_cancel", stamp.utterance) }
            override fun close() { closed++; trace("tts_closed") }
        },
        player = object : VoicePlayer {
            override suspend fun play(audio: VoicePcm, stamp: VoiceStamp, onLevel: (Float) -> Unit) {
                playing = true; trace("play_chunk_start", stamp.utterance)
                try { onLevel(audio.rms() * 5); delay(500); onLevel(0f) }
                finally { playing = false; trace("play_chunk_end", stamp.utterance) }
            }
            override fun stop(stamp: VoiceStamp?) { playing = false; trace("player_stop_flush", stamp?.utterance) }
            override fun close() { closed++; trace("player_closed") }
        },
    )
}
