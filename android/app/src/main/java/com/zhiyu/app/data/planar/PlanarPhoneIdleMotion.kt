package com.zhiyu.app.data.planar

import rig2d.planar.PlanarAnimation
import rig2d.planar.PlanarContract
import rig2d.planar.PlanarParameters
import kotlin.math.sin

/** The portable idle was subpixel after a large source portrait was fitted into a phone preview. */
internal object PlanarPhoneIdleMotion {
    fun sample(contract: PlanarContract, seconds: Double, mouth: Float, idle: Boolean, hasHead: Boolean): PlanarParameters {
        val original = PlanarAnimation.sample(contract, seconds, mouth, idle)
        if (!idle || !hasHead) return original
        val local = mapOf("headX" to wave(seconds, 1.25) * .75, "headY" to wave(seconds, .91) * .35)
        val ranges = contract.parameters
        return PlanarParameters(contract, original.toMap() + local.mapValues { (name, normalized) ->
            val range = ranges.getValue(name)
            val extent = if (normalized >= 0) range.maximum - range.default else range.default - range.minimum
            (range.default + normalized * extent).toFloat().coerceIn(range.minimum, range.maximum)
        })
    }

    private fun wave(seconds: Double, frequency: Double) =
        sin((seconds % (2 * Math.PI / frequency)) * frequency)
}
