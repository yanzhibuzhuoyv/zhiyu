package com.zhiyu.app.data

import android.media.AudioTrack
import android.media.PlaybackParams
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.VoiceTuningSettings
import kotlin.math.abs

/** Android's time stretch changes pitch at speed 1; VITS already controls speaking duration. */
internal object LocalVoicePlaybackTuning {
    fun parameters(value: VoiceTuningSettings): PlaybackParams = PlaybackParams().allowDefaults()
        .setSpeed(1f).setPitch(value.pitchRatio).setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)

    fun apply(output: AudioTrack, value: VoiceTuningSettings) {
        if (value.pitchSemitones == 0f) return // Keep the original synthesis/playback path byte for byte.
        try {
            output.playbackParams = parameters(value)
            val applied = output.playbackParams
            check(abs(applied.speed - 1f) < .0001f && abs(applied.pitch - value.pitchRatio) < .0001f)
        } catch (failure: RuntimeException) {
            throw ApiFailure(0, "这台设备暂时无法使用这个音高，可以调回原音高再试听", "voice_pitch_unavailable")
        }
    }
}
