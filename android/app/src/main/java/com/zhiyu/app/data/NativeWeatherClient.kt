package com.zhiyu.app.data

import com.zhiyu.app.domain.ApproximateLocation
import com.zhiyu.app.domain.CareRules
import com.zhiyu.app.domain.WeatherSnapshot
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

fun interface NativeWeatherService { suspend fun fetch(point: ApproximateLocation): WeatherSnapshot }

/** The weather service receives a rounded location, never an AI key or chat text. */
class NativeWeatherClient(
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : NativeWeatherService {
    override suspend fun fetch(point: ApproximateLocation): WeatherSnapshot = try {
        withTimeout(12_000) { coroutineScope {
            val connection = URL(CareRules.weatherUrl(point)).openConnection() as HttpURLConnection
            suspendCancellableCoroutine { continuation ->
                val worker = launch(Dispatchers.IO) {
                    try {
                        val result = read(connection)
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
                continuation.invokeOnCancellation { connection.disconnect(); worker.cancel() }
            }
        } }
    } catch (_: TimeoutCancellationException) {
        throw IllegalStateException("天气获取超时，聊天仍可使用。稍后再刷新即可。")
    }

    private suspend fun read(connection: HttpURLConnection): WeatherSnapshot {
        try {
            coroutineContext.ensureActive()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IllegalStateException("天气服务暂时不可用，请稍后刷新")
            require(connection.contentType?.substringBefore(';') == "application/json") { "天气服务返回格式无效" }
            require(connection.contentLengthLong <= CareRules.MAX_RESPONSE_BYTES) { "天气数据过大" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= CareRules.MAX_RESPONSE_BYTES) { "天气数据过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            coroutineContext.ensureActive()
            return decode(bytes.toString(Charsets.UTF_8), now())
        } catch (_: SocketTimeoutException) {
            throw IllegalStateException("天气获取超时，聊天仍可使用。稍后再刷新即可。")
        } catch (_: java.io.IOException) {
            throw IllegalStateException("暂时连不上天气服务，聊天仍可使用。")
        } finally { connection.disconnect() }
    }

    internal fun decode(body: String, fetchedAt: Long): WeatherSnapshot {
        try {
            val json = JSONObject(body)
            val units = json.getJSONObject("current_units")
            require(units.getString("temperature_2m") == "°C" && units.getString("apparent_temperature") == "°C" &&
                units.getString("wind_speed_10m") == "km/h" && units.getString("precipitation") == "mm" && units.getString("time") == "unixtime")
            val current = json.getJSONObject("current")
            val snapshot = CareRules.weather(WeatherSnapshot(number(current, "time").also { require(it % 1 == 0.0) }.toLong(), fetchedAt,
                number(current, "temperature_2m"), number(current, "apparent_temperature"),
                number(current, "weather_code").also { require(it % 1 == 0.0) }.toInt(),
                number(current, "precipitation"), number(current, "wind_speed_10m")))
            require(CareRules.fresh(snapshot, fetchedAt))
            return snapshot
        } catch (_: org.json.JSONException) { throw IllegalArgumentException("天气数据不完整，请稍后刷新") }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("天气数据无效或已经过期，请稍后刷新") }
    }

    private fun number(json: JSONObject, key: String): Double {
        val value = json.get(key)
        require(value is Number) { "天气数据类型无效" }
        return value.toDouble().also { require(it.isFinite()) }
    }
}
