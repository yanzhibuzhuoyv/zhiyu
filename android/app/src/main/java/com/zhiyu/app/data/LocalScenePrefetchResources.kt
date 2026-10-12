package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.data.local.qwen.QwenDeviceResources
import com.zhiyu.app.domain.LocalScenePrefetchResources
import com.zhiyu.app.domain.QwenResourceRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** An unreadable global CPU counter stays unknown; own-process CPU is not a substitute for phone load. */
class LocalSceneResourceReader(context: Context) {
    private val resources = QwenDeviceResources(context.applicationContext)
    suspend fun read(): LocalScenePrefetchResources = withContext(Dispatchers.IO) {
        val first = cpu()
        delay(250)
        val second = cpu()
        val load = if (first != null && second != null) {
            val total = second.first - first.first
            val idle = second.second - first.second
            if (total > 0 && idle in 0..total) 1.0 - idle.toDouble() / total else null
        } else null
        val hardware = try { resources.read() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            return@withContext LocalScenePrefetchResources(load, null, null)
        }
        val thermal = hardware.temperatureDeciC?.let { it in 1..400 } == true && hardware.thermalStatus?.let { it in 0..1 } == true
        val memory = !hardware.lowMemory && hardware.availableMemory >= 768 * QwenResourceRules.MIB &&
            hardware.freeStorage >= 256 * QwenResourceRules.MIB
        LocalScenePrefetchResources(load, thermal, memory)
    }
    private fun cpu(): Pair<Long, Long>? = try {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() }
        require(line != null && line.length in 1..512)
        val tokens = line.trim().split(Regex("\\s+"))
        require(tokens.size in 5..16 && tokens.first() == "cpu")
        val counters = tokens.drop(1).take(8).map { it.toLong().also { count -> require(count >= 0) } }
        counters.sum() to (counters[3] + counters.getOrElse(4) { 0 })
    } catch (_: Exception) { null }
}
