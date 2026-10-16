package com.zhiyu.app.notifications

import android.content.Context
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.domain.ProactiveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A local settings commit outlives its screen. No provider request or persistent service. */
internal object ContactPreferenceWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()

    fun submit(context: Context, id: String, preferences: ProactivePreferences,
        service: ProactiveService): Deferred<ProactivePreferences> {
        val app = context.applicationContext ?: context
        // Enter the mutex in invocation order before suspension. Only local IO runs on IO.
        return scope.async(start = CoroutineStart.UNDISPATCHED) { serial.withLock {
            withContext(Dispatchers.IO) { withTimeout(15_000) {
                val state = ProactiveWorkStore(app)
                val requested = preferences.copy(enabled = preferences.enabled && ProactiveNotifications.allowed(app))
                if (!requested.enabled) {
                    state.setEnabled(id, false)
                    ProactiveScheduler.cancel(app)
                }
                var accepted = service.savePreferences(id, requested)
                // A grant can be revoked while the local archive/settings are being checked.
                if (accepted.enabled && !ProactiveNotifications.allowed(app)) {
                    state.setEnabled(id, false)
                    ProactiveScheduler.cancel(app)
                    accepted = service.savePreferences(id, accepted.copy(enabled = false))
                }
                state.setEnabled(id, accepted.enabled)
                if (accepted.enabled) ProactiveScheduler.schedule(app, id) else ProactiveScheduler.cancel(app)
                accepted
            } }
        } }
    }
}
