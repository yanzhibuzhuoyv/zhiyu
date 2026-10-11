package com.zhiyu.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.zhiyu.app.domain.CarePreferenceState
import com.zhiyu.app.domain.CareRules
import com.zhiyu.app.domain.WeatherSnapshot
import com.zhiyu.app.domain.CompanionPermission

data class CareLoadResult(val state: CarePreferenceState, val warning: String? = null)

/** Stores only current weather facts and the opt-in switch; coordinates are never persisted. */
class CarePreferences(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences("weather_care", Context.MODE_PRIVATE)

    fun load(): CareLoadResult {
        return try {
            val enabled = preferences.getBoolean("enabled", false)
            val weather = if (!enabled || !preferences.contains("observed_at")) null else CareRules.weather(WeatherSnapshot(
                preferences.getLong("observed_at", 0), preferences.getLong("fetched_at", 0),
                preferences.getString("temperature", "")!!.toDouble(), preferences.getString("feels_like", "")!!.toDouble(),
                preferences.getInt("weather_code", -1), preferences.getString("precipitation", "")!!.toDouble(),
                preferences.getString("wind_speed", "")!!.toDouble(),
            ))
            CareLoadResult(CarePreferenceState(enabled, weather))
        } catch (_: IllegalArgumentException) {
            CareLoadResult(CarePreferenceState(), "天气设置暂时无法读取，请重新开启天气关心。")
        } catch (_: ClassCastException) {
            CareLoadResult(CarePreferenceState(), "天气设置格式有误，请重新开启天气关心。")
        }
    }

    fun save(state: CarePreferenceState) {
        state.weather?.let(CareRules::weather)
        val editor = preferences.edit().clear().putBoolean("enabled", state.enabled)
        state.weather?.takeIf { state.enabled }?.let {
            editor.putLong("observed_at", it.observedAt).putLong("fetched_at", it.fetchedAt)
                .putString("temperature", it.temperatureC.toString()).putString("feels_like", it.feelsLikeC.toString())
                .putInt("weather_code", it.weatherCode).putString("precipitation", it.precipitationMm.toString())
                .putString("wind_speed", it.windSpeedKmh.toString())
        }
        check(editor.commit()) { "天气设置没有保存成功，请重试" }
    }
}

class NativeCareContext(context: Context, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val appContext = context.applicationContext
    private val preferences = CarePreferences(appContext)

    fun prompt(): String {
        val allowed = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val loaded = preferences.load()
        if (loaded.warning != null) return ""
        return CareRules.prompt(loaded.state, now(), allowed)
    }
}

/** One durable request per capability; denial is never used as a reason to open system settings. */
class CompanionPermissionRequests(context: Context) {
    private val preferences = (context.applicationContext ?: context).getSharedPreferences("companion_permission_requests_v1", Context.MODE_PRIVATE)
    fun requested(): Set<CompanionPermission> = CompanionPermission.entries.filterTo(mutableSetOf()) {
        preferences.getBoolean(it.name, false)
    }
    fun markRequested(permission: CompanionPermission) {
        check(preferences.edit().putBoolean(permission.name, true).commit()) { "权限申请状态没有保存成功" }
    }
    fun clearRequested(permission: CompanionPermission) {
        check(preferences.edit().remove(permission.name).commit()) { "权限申请状态没有恢复成功" }
    }
}
