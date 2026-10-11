package com.zhiyu.app.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.provider.CalendarContract
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.zhiyu.app.domain.ApproximateLocation
import com.zhiyu.app.domain.CalendarDraft
import com.zhiyu.app.domain.CareRules
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

fun interface CareLocationSource { suspend fun current(): ApproximateLocation }

/** Requests one foreground approximate fix, and unregisters on timeout or cancellation. */
class ForegroundLocationSource(context: Context) : CareLocationSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun current(): ApproximateLocation = withTimeout(10_000) {
        check(ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "尚未允许大致位置，聊天仍可正常使用" }
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && manager.allProviders.contains("fused") && manager.isProviderEnabled("fused") -> "fused"
            manager.allProviders.contains(LocationManager.NETWORK_PROVIDER) && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> throw IllegalStateException("手机暂时无法取得大致位置，请开启系统定位后再试")
        }
        if (Build.VERSION.SDK_INT >= 30) currentModern(provider) else currentLegacy(provider)
    }

    @RequiresApi(30)
    private suspend fun currentModern(provider: String): ApproximateLocation = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(appContext)) { location ->
                if (continuation.isActive) {
                    if (location == null) continuation.resumeWithException(IllegalStateException("暂时没能取得位置，请稍后刷新天气"))
                    else continuation.resumeWith(runCatching { CareRules.approximate(ApproximateLocation(location.latitude, location.longitude)) })
                }
            }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("定位权限已经关闭，聊天仍可使用"))
        }
    }

    private suspend fun currentLegacy(provider: String): ApproximateLocation = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resumeWith(runCatching { CareRules.approximate(ApproximateLocation(location.latitude, location.longitude)) })
            }
            override fun onProviderDisabled(provider: String) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("系统定位已经关闭，请开启后再试"))
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy Android callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        try { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        catch (_: SecurityException) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("定位权限已经关闭，聊天仍可使用"))
        }
    }
}

object CalendarCareActions {
    fun insert(draft: CalendarDraft): Intent {
        val valid = CareRules.calendar(draft)
        val (start, end) = CareRules.calendarTimes(valid)
        return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, valid.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, valid.description)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
    }
}
