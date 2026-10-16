package rig2d.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One response, three independently scheduled stages; bounded channels preserve spoken order. */
internal class VoiceReplyPipeline(private val ports: DuplexPorts, private val config: DuplexConfig) {
    suspend fun run(transcript: String, stamp: VoiceStamp, active: () -> Boolean,
        onLevel: (Float) -> Unit) = coroutineScope {
        val text = Channel<String>(config.textQueueCapacity)
        val audio = Channel<VoicePcm>(config.audioQueueCapacity)
        launch {
            try { produceText(transcript, stamp, active, text) }
            finally { text.close() }
        }
        launch {
            try { synthesize(stamp, active, text, audio) }
            finally { audio.close() }
        }
        try {
            for (chunk in audio) {
                requireActive(active)
                voiceStage(VoiceFault.PLAYBACK) {
                    ports.player.play(chunk, stamp) { level ->
                        if (active()) onLevel(if (level.isFinite()) level.coerceIn(0f, 1f) else 0f)
                    }
                }
                requireActive(active)
            }
        } finally { text.cancel(); audio.cancel() }
    }

    private suspend fun produceText(transcript: String, stamp: VoiceStamp, active: () -> Boolean,
        output: Channel<String>) = voiceStage(VoiceFault.GENERATION) {
        val chunks = SentenceChunker(config.chunkCodePoints, config.maxReplyCodePoints)
        ports.tokens.tokens(transcript, stamp).collect { token ->
            requireActive(active)
            for (chunk in chunks.accept(token)) { requireActive(active); output.send(chunk) }
        }
        requireActive(active)
        for (chunk in chunks.finish()) { requireActive(active); output.send(chunk) }
    }

    private suspend fun synthesize(stamp: VoiceStamp, active: () -> Boolean,
        input: Channel<String>, output: Channel<VoicePcm>) = voiceStage(VoiceFault.SYNTHESIS) {
        for (text in input) {
            requireActive(active)
            val audio = ports.synthesizer.synthesize(text, stamp, active)
            requireActive(active)
            output.send(audio)
        }
    }

    private suspend fun requireActive(active: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException("Stale voice response")
    }
}
