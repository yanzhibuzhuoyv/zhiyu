package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.WeatherRuntimeContext
import com.zhiyu.app.domain.CareRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NativeWeatherRefresh(context: Context,
    private val location: CareLocationSource = ForegroundLocationSource(context),
    private val weather: NativeWeatherService = NativeWeatherClient(),
    private val allowed: () -> Boolean = {
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    },
    private val foreground: () -> Boolean = com.zhiyu.app.notifications.ProactiveForeground::isVisible,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val store = CarePreferences(context)
    private val gate = Mutex()
    private var lastAttempt: Long? = null
    suspend fun forQuestion(text: String) {
        if (!WeatherRuntimeContext.requested(text)) return
        try {
            withTimeoutOrNull(6_000) { gate.withLock {
                val time = now()
                val loaded = store.load()
                if (loaded.warning != null || !loaded.state.enabled || !allowed() || !foreground()) return@withLock
                if (loaded.state.weather?.let { CareRules.fresh(it, time) && time - it.fetchedAt in 0..CareRules.REFRESH_INTERVAL_SECONDS } == true) return@withLock
                if (lastAttempt?.let { time - it in 0..30 } == true) return@withLock
                lastAttempt = time
                val point = CareRules.approximate(location.current())
                if (!allowed() || !foreground() || !store.load().state.enabled) return@withLock
                val snapshot = CareRules.weather(weather.fetch(point))
                currentCoroutineContext().ensureActive()
                store.publishWeatherIfEnabled(snapshot, now()) { allowed() && foreground() }
            } }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            android.util.Log.w("ZhiYuCare", "Current weather refresh unavailable; no weather inferred")
        }
    }
}
