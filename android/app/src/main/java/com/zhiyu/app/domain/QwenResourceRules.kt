package com.zhiyu.app.domain

data class QwenResources(val temperatureDeciC: Int?, val thermalStatus: Int?,
    val availableMemory: Long, val lowMemory: Boolean, val freeStorage: Long)

/** Stock priority can bypass idle preference, never these resource limits. */
object QwenResourceRules {
    const val MIB = 1024L * 1024
    fun canStart(stage: String, value: QwenResources): Boolean {
        val needed = when (stage) { "text" -> 768 * MIB; "sample" -> 4400 * MIB; "decode" -> 3200 * MIB
            else -> throw IllegalArgumentException("未知本机制图阶段") }
        return knownAndCool(value, 400) && !value.lowMemory && value.availableMemory >= needed && value.freeStorage >= 256 * MIB
    }
    fun mustStop(value: QwenResources): Boolean = !knownAndCool(value, 430) || value.lowMemory ||
        value.availableMemory < 256 * MIB || value.freeStorage < 64 * MIB
    private fun knownAndCool(value: QwenResources, max: Int): Boolean = value.temperatureDeciC?.let { it in 1..max } == true &&
        value.thermalStatus?.let { it in 0..1 } == true
}
