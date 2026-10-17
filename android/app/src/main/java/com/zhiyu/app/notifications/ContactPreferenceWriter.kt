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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = Mutex()

    fun submit(context: Context, id: String, preferences: ProactivePreferences,
        service: ProactiveService): Deferred<ProactivePreferences> {
        val app = context.applicationContext ?: context
        // Enter the mutex in invocation order before suspension. Only local IO runs on IO.
        return scope.async(start = CoroutineStart.UNDISPATCHED) { serial.withLock {
            withContext(Dispatchers.IO) { withTimeout(15_000) {
                val state = ProactiveWorkStore(app)
                val previouslyEnabled = state.enabled(id)
                // Android permission gates execution; revocation must not rewrite an
                // already authorized relationship preference into a user disable.
                val requested = preferences
                if (!requested.enabled) {
                    state.setEnabled(id, false)
                    ProactiveScheduler.cancel(app)
                }
                val accepted = service.savePreferences(id, requested)
                // Injected/legacy services may still have an independent preferences store.
                // The default local service already committed this exact canonical value.
                if (state.enabled(id) != accepted.enabled) state.setEnabled(id, accepted.enabled)
                if (accepted.enabled && ProactiveNotifications.allowed(app)) {
                    if (!previouslyEnabled) ProactiveScheduler.onContactGranted(app, id)
                    else ProactiveScheduler.schedule(app, id)
                }
                else ProactiveScheduler.cancel(app)
                accepted
            } }
        } }
    }
}
