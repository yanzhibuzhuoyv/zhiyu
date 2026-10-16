package rig2d.voice

import kotlinx.coroutines.flow.Flow
import kotlin.math.sqrt

/** A session epoch and utterance epoch; never reuse either to tag an asynchronous callback. */
data class VoiceStamp(val session: Long, val utterance: Long)

/** Immutable normalized mono PCM. Ports must not retain an externally mutable backing array. */
class VoicePcm(samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate in 8000..48000) { "Unsupported mono PCM rate" }
        require(samples.size in 1..sampleRate * 30) { "PCM chunk is empty or too large" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Invalid normalized PCM" }
    }
    private val data = samples.copyOf()
    val sampleCount: Int get() = data.size
    val durationMillis: Long get() = sampleCount * 1000L / sampleRate
    fun samplesCopy(): FloatArray = data.copyOf()
    fun rms(): Float = sqrt(data.sumOf { it.toDouble() * it } / data.size).toFloat()
}

data class DuplexConfig(
    val startSpeechMillis: Int = 60,
    val endSilenceMillis: Int = 240,
    val minSpeechMillis: Int = 100,
    val maxUtteranceMillis: Int = 30_000,
    val preRollMillis: Int = 200,
    val captureFrameMaxMillis: Int = 100,
    val captureQueueCapacity: Int = 16,
    val textQueueCapacity: Int = 2,
    val audioQueueCapacity: Int = 2,
    val chunkCodePoints: Int = 80,
    val maxReplyCodePoints: Int = 8192,
    val maxTranscriptCodePoints: Int = 8192,
) {
    fun checked(): DuplexConfig {
        require(startSpeechMillis in 10..1000)
        require(endSilenceMillis in 10..2000)
        require(minSpeechMillis in startSpeechMillis..2000)
        require(maxUtteranceMillis in minSpeechMillis..60000)
        require(preRollMillis in startSpeechMillis..2000)
        require(captureFrameMaxMillis in 1..1000)
        require(captureQueueCapacity in 1..128)
        require(textQueueCapacity in 1..16 && audioQueueCapacity in 1..16)
        require(chunkCodePoints in 1..120)
        require(maxReplyCodePoints in chunkCodePoints..32768)
        require(maxTranscriptCodePoints in 1..32768)
        return this
    }
}

/** Adapters provide echo-cancelled PCM; this port does not pretend an RMS gate is acoustic AEC. */
interface VoiceCapture : AutoCloseable {
    fun frames(): Flow<VoicePcm>
}

fun interface VoiceActivityDetector {
    fun isSpeech(frame: VoicePcm): Boolean
}

class EnergyVoiceActivityDetector(private val threshold: Float = 0.025f) : VoiceActivityDetector {
    init { require(threshold.isFinite() && threshold > 0f && threshold <= 1f) }
    override fun isSpeech(frame: VoicePcm): Boolean = frame.rms() >= threshold
}

interface VoiceRecognition : AutoCloseable {
    suspend fun accept(frame: VoicePcm): String
    suspend fun finish(): String
}

interface VoiceRecognizer : AutoCloseable {
    suspend fun open(stamp: VoiceStamp): VoiceRecognition
}

/** Flow collection and cancel must cancel the network request; never log Key, transcript or prompt. */
interface VoiceTokenSource : AutoCloseable {
    fun tokens(transcript: String, stamp: VoiceStamp): Flow<String>
    fun cancel(stamp: VoiceStamp)
}

/** Native inference must poll active; cancel must be a fast signal, not a blocking join. */
interface VoiceSynthesizer : AutoCloseable {
    suspend fun synthesize(text: String, stamp: VoiceStamp, active: () -> Boolean): VoicePcm
    fun cancel(stamp: VoiceStamp)
}

/** play awaits the last actual sample. stop is nonblocking and must pause/flush actual hardware. */
interface VoicePlayer : AutoCloseable {
    suspend fun play(audio: VoicePcm, stamp: VoiceStamp, onLevel: (Float) -> Unit)
    fun stop(stamp: VoiceStamp?)
}

data class DuplexPorts(
    val capture: VoiceCapture,
    val recognizer: VoiceRecognizer,
    val tokens: VoiceTokenSource,
    val synthesizer: VoiceSynthesizer,
    val player: VoicePlayer,
    val vad: VoiceActivityDetector = EnergyVoiceActivityDetector(),
)

/** Fresh ports per start; factory owns rollback if acquiring the bundle fails. */
fun interface DuplexPortsFactory { suspend fun open(): DuplexPorts }

enum class VoiceFault { CAPTURE, CAPTURE_OVERFLOW, RECOGNITION, GENERATION, SYNTHESIS, PLAYBACK, RELEASE }

sealed interface VoiceEvent {
    data class Started(val session: Long) : VoiceEvent
    data class Listening(val stamp: VoiceStamp) : VoiceEvent
    data class Transcript(val stamp: VoiceStamp, val text: String, val final: Boolean) : VoiceEvent
    data class Interrupted(val stamp: VoiceStamp) : VoiceEvent
    data class ReplyComplete(val stamp: VoiceStamp) : VoiceEvent
    data class Fault(val session: Long, val fault: VoiceFault) : VoiceEvent
    data class Stopped(val session: Long) : VoiceEvent
}

/** Sanitized failure classification. Never carry a provider exception, prompt or audio in an event. */
internal class VoiceStageFailure(val fault: VoiceFault) : RuntimeException(fault.name)

internal suspend fun <T> voiceStage(fault: VoiceFault, block: suspend () -> T): T = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: VoiceStageFailure) {
    throw failure
} catch (_: Exception) {
    throw VoiceStageFailure(fault)
}

internal fun String.checkedTranscript(maxCodePoints: Int): String {
    require(length <= maxCodePoints * 2)
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index++]
        if (first.isHighSurrogate()) { require(index < length && this[index].isLowSurrogate()); index++ }
        else require(!first.isLowSurrogate())
        require(++count <= maxCodePoints)
    }
    return trim()
}
