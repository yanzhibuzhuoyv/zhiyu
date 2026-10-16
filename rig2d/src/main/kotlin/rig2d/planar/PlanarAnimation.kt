package rig2d.planar

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Deterministic idle and supplied RMS animation, without capture or playback dependencies. */
object PlanarAnimation {
    fun sample(
        contract: PlanarContract,
        seconds: Double,
        audioRms: Float = 0f,
        idleEnabled: Boolean = true,
        gazeX: Float = 0f,
        gazeY: Float = 0f,
    ): PlanarParameters {
        require(seconds.isFinite() && seconds >= 0) { "Animation time must be finite and nonnegative" }
        require(audioRms.isFinite() && audioRms in 0f..1f) { "RMS must be finite and normalized" }
        require(gazeX.isFinite() && gazeX in -1f..1f && gazeY.isFinite() && gazeY in -1f..1f) {
            "Gaze must be finite and normalized"
        }
        val phase = seconds % 60.0
        val blink = if (idleEnabled) max(0.0, 1.0 - abs(seconds % 4.7 - 4.3) / .12) else 0.0
        val normalized = mapOf(
            "headX" to gazeX * .7 + if (idleEnabled) sin(phase * .67) * .08 else 0.0,
            "headY" to gazeY * .7 + if (idleEnabled) sin(phase * .43) * .05 else 0.0,
            "tilt" to if (idleEnabled) sin(phase * .37) * .035 else 0.0,
            "blinkLeft" to blink,
            "blinkRight" to blink,
            "mouth" to audioRms.toDouble().pow(.65),
            "breath" to if (idleEnabled) (.5 + .5 * sin(phase * 1.2)) * .35 else 0.0,
            "sway" to if (idleEnabled) sin(phase * .83) * .08 else 0.0,
        )
        return PlanarParameters(contract, normalized.mapValues { (name, value) -> scale(contract.range(name), value) })
    }

    private fun scale(range: ParameterRange, normalized: Double): Float {
        val fraction = normalized.coerceIn(-1.0, 1.0)
        val distance = if (fraction >= 0) range.maximum.toDouble() - range.default else
            range.default.toDouble() - range.minimum
        return (range.default + fraction * distance).toFloat().coerceIn(range.minimum, range.maximum)
    }
}
