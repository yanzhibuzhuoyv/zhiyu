package rig2d.voice

data class VadDecision(val started: Boolean = false, val ended: Boolean = false, val accepted: Boolean = false)

/** Endpoint policy only; speech likelihood comes from an injectable detector, not this state machine. */
class VadEndpointDetector(config: DuplexConfig = DuplexConfig()) {
    private val rules = config.checked()
    private var candidateMillis = 0
    private var speechMillis = 0
    private var silenceMillis = 0
    private var elapsedMillis = 0
    private var speaking = false

    fun accept(speech: Boolean, durationMillis: Int): VadDecision {
        require(durationMillis in 1..rules.captureFrameMaxMillis)
        var started = false
        if (!speaking) {
            candidateMillis = if (speech) candidateMillis + durationMillis else 0
            if (candidateMillis < rules.startSpeechMillis) return VadDecision()
            speaking = true
            speechMillis = candidateMillis
            elapsedMillis = candidateMillis
            started = true
        } else {
            elapsedMillis += durationMillis
            if (speech) speechMillis += durationMillis
        }
        silenceMillis = if (speech) 0 else silenceMillis + durationMillis
        val ended = silenceMillis >= rules.endSilenceMillis || elapsedMillis >= rules.maxUtteranceMillis
        val accepted = ended && speechMillis >= rules.minSpeechMillis
        if (ended) reset()
        return VadDecision(started, ended, accepted)
    }

    fun reset() {
        candidateMillis = 0; speechMillis = 0; silenceMillis = 0; elapsedMillis = 0; speaking = false
    }
}
