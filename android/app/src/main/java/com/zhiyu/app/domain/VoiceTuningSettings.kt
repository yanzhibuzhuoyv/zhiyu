package com.zhiyu.app.domain

import kotlin.math.pow

/** Duration, musical pitch and gain are independent; synthesis never introduces random noise. */
data class VoiceTuningSettings(
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val volume: Float = 1f,
) {
    init {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) { "语速需要在 0.75 到 1.35 倍之间" }
        require(pitchSemitones.isFinite() && pitchSemitones in MIN_PITCH..MAX_PITCH) { "音高需要在正负 4 个半音之间" }
        require(volume.isFinite() && volume in 0f..1f) { "声音音量需要在 0 到 100% 之间" }
    }

    val pitchRatio: Float get() = 2.0.pow(pitchSemitones.toDouble() / 12.0).toFloat()

    companion object {
        const val MIN_SPEED = .75f
        const val MAX_SPEED = 1.35f
        const val MIN_PITCH = -4f
        const val MAX_PITCH = 4f
    }
}
