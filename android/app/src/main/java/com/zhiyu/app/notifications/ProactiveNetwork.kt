package com.zhiyu.app.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** A domestic API may be reachable even when Android's external validation probe fails. */
internal object ProactiveNetwork {
    fun constraints(existing: Constraints = Constraints.NONE): Constraints {
        val builder = Constraints.Builder().setRequiresCharging(existing.requiresCharging())
            .setRequiresDeviceIdle(existing.requiresDeviceIdle())
            .setRequiresBatteryNotLow(existing.requiresBatteryNotLow())
            .setRequiresStorageNotLow(existing.requiresStorageNotLow())
        existing.contentUriTriggers.forEach { builder.addContentUriTrigger(it.uri, it.isTriggeredForDescendants) }
        if (existing.contentTriggerUpdateDelayMillis >= 0)
            builder.setTriggerContentUpdateDelay(existing.contentTriggerUpdateDelayMillis, TimeUnit.MILLISECONDS)
        if (existing.contentTriggerMaxDelayMillis >= 0)
            builder.setTriggerContentMaxDelay(existing.contentTriggerMaxDelayMillis, TimeUnit.MILLISECONDS)
        if (Build.VERSION.SDK_INT < 28) return builder.setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()
        val network = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= 30) network.clearCapabilities()
        else network.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val request = network.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        return builder.setRequiredNetworkRequest(request, NetworkType.NOT_REQUIRED).build()
    }

    /** The old platform constraint that required Android's validation probe. */
    fun isLegacy(constraints: Constraints): Boolean =
        constraints.requiredNetworkType == NetworkType.CONNECTED &&
            (Build.VERSION.SDK_INT < 28 || constraints.requiredNetworkRequest == null)

    fun available(context: Context): Boolean = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        active != null && manager.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        false
    }
}

/** Repairs never block the caller; a worker may itself be running inside WorkManager's executor. */
internal object BackgroundNetworkRepair {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(label: String, block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("ZhiYuBackground", "$label deferred; stored intent is retained", failure)
            }
        }
    }
}
