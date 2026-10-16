package rig2d.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** NOT_REAL_AUDIO: every frame and decoded string below is a synthetic lifecycle fixture. */
internal class DuplexFakePorts {
    val input = Channel<VoicePcm>(64)
    var reads = 0
    var captureClosed = 0
    var captureFinally = 0
    var recognizerClosed = 0
    var recognitionClosed = 0
    var recognitionAccepts = 0
    var recognitionText = "NOT_REAL_TRANSCRIPT"
    var recognitionFailure = false
    var acceptGate: CompletableDeferred<Unit>? = null
    var tokensClosed = 0
    var generationCalls = 0
    var generationCancels = 0
    var generationFinally = 0
    var modelFlow: (VoiceStamp) -> Flow<String> = { flow { emit("第一句。"); emit("第二句！") } }
    var synthClosed = 0
    var synthCancels = 0
    val synthesized = mutableListOf<Pair<VoiceStamp, String>>()
    var synthGate: CompletableDeferred<Unit>? = null
    var synthFailure = false
    var playClosed = 0
    var playStops = 0
    var playing = false
    var playMillis = 5000L
    var playFailure = false
    val played = mutableListOf<VoiceStamp>()
    val levelCallbacks = mutableListOf<(Float) -> Unit>()
    val events = mutableListOf<VoiceEvent>()
    val mouths = mutableListOf<Pair<VoiceStamp, Float>>()

    val bundle = DuplexPorts(
        capture = object : VoiceCapture {
            override fun frames() = flow {
                try { for (frame in input) { reads++; emit(frame) } }
                finally { captureFinally++ }
            }
            override fun close() { captureClosed++ }
        },
        recognizer = object : VoiceRecognizer {
            override suspend fun open(stamp: VoiceStamp): VoiceRecognition = object : VoiceRecognition {
                override suspend fun accept(frame: VoicePcm): String {
                    recognitionAccepts++; acceptGate?.await()
                    if (recognitionFailure) error("NOT_SECRET: recognizer fixture failed")
                    return recognitionText
                }
                override suspend fun finish() = recognitionText
                override fun close() { recognitionClosed++ }
            }
            override fun close() { recognizerClosed++ }
        },
        tokens = object : VoiceTokenSource {
            override fun tokens(transcript: String, stamp: VoiceStamp) = flow {
                generationCalls++
                try { modelFlow(stamp).collect { emit(it) } }
                finally { generationFinally++ }
            }
            override fun cancel(stamp: VoiceStamp) { generationCancels++ }
            override fun close() { tokensClosed++ }
        },
        synthesizer = object : VoiceSynthesizer {
            override suspend fun synthesize(text: String, stamp: VoiceStamp, active: () -> Boolean): VoicePcm {
                synthesized += stamp to text
                synthGate?.await()
                if (synthFailure) error("NOT_SECRET: synthesizer fixture failed")
                check(active())
                return VoicePcm(FloatArray(320) { 0.1f }, 16000)
            }
            override fun cancel(stamp: VoiceStamp) { synthCancels++ }
            override fun close() { synthClosed++ }
        },
        player = object : VoicePlayer {
            override suspend fun play(audio: VoicePcm, stamp: VoiceStamp, onLevel: (Float) -> Unit) {
                played += stamp; levelCallbacks += onLevel; playing = true
                try {
                    onLevel(0.5f)
                    if (playFailure) error("NOT_SECRET: playback fixture failed")
                    delay(playMillis)
                    onLevel(0f)
                } finally { playing = false }
            }
            override fun stop(stamp: VoiceStamp?) { playStops++; playing = false }
            override fun close() { playClosed++ }
        },
    )

    fun session(scope: CoroutineScope, config: DuplexConfig = DuplexConfig()) =
        DuplexCallSession(scope, { bundle }, config, events::add) { stamp, level -> mouths += stamp to level }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun feed(scope: TestScope, speech: Boolean, count: Int = 1) {
        repeat(count) { input.send(pcm(speech)); scope.runCurrent() }
    }

    suspend fun utterance(scope: TestScope) { feed(scope, true, 5); feed(scope, false, 12) }

    companion object { fun pcm(speech: Boolean) = VoicePcm(FloatArray(320) { if (speech) 0.1f else 0f }, 16000) }
}
