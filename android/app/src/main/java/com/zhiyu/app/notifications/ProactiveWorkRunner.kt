package com.zhiyu.app.notifications

import android.content.Context
import com.zhiyu.app.data.ContactDeliveryGate
import com.zhiyu.app.data.ContactPauseStore
import com.zhiyu.app.data.LocalProactiveService
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.ContactDeliveryBlockedException
import com.zhiyu.app.domain.CredentialStore
import com.zhiyu.app.domain.ProactiveDelivery
import com.zhiyu.app.domain.ProactiveService
import com.zhiyu.app.domain.ProactiveWorkOutcome
import com.zhiyu.app.domain.ProactiveRunRecord
import java.time.Instant

/** The Worker and integration test enter this same due/service/archive/notification path. */
internal class ProactiveWorkRunner(
    private val context: Context,
    private val credentials: CredentialStore = appCredentialStore(context),
    service: ProactiveService? = null,
    private val networkAvailable: () -> Boolean = { ProactiveNetwork.available(context) },
    private val now: () -> Instant = Instant::now,
) {
    private val deliveryService by lazy { service ?: LocalProactiveService(context, credentials) }
    suspend fun run(id: String, continueChain: Boolean = false): ProactiveWorkOutcome {
        val state = ProactiveWorkStore(context)
        val due = state.lastRun(id)?.nextDue
        if (state.enabled(id) && due?.isAfter(now()) == true) {
            ProactiveScheduler.next(context, id, now(), continueChain)
            return ProactiveWorkOutcome.COMPLETE
        }
        val pauses = ContactPauseStore(context)
        val contact = pauses.read(id)
        if (state.enabled(id) && ProactiveNotifications.allowed(context) &&
            ContactDeliveryGate.reason(pauses, id, contact) == null && !ProactiveForeground.isVisible() && !networkAvailable()) {
            // WorkManager CONNECTED also requires platform validation on modern Android.
            // Check the actual active internet network here instead; HTTP still decides
            // reachability. A truly offline attempt never allocates a paid request identity.
            state.recordRun(id, ProactiveRunRecord(now(), "retry", null))
            return ProactiveWorkOutcome.RETRY
        }
        val delivery = ProactiveDelivery(deliveryService, state, {
            ProactiveNotifications.allowed(context) && ContactDeliveryGate.reason(pauses, id, contact) == null
        }, ProactiveForeground::isVisible) { message ->
            try { pauses.withDeliveryAllowed(id, contact.revision) {
                if (ContactDeliveryGate.reason(pauses, id, contact, message) == null)
                    ProactiveNotifications.post(context, id, message, credentials.readBinding()?.profile?.name)
            } } catch (_: ContactDeliveryBlockedException) { /* A new user command cancels this notification. */ }
        }
        return delivery.run(id).also { outcome ->
            if (outcome != ProactiveWorkOutcome.RETRY) ProactiveScheduler.next(context, id, now(), continueChain)
        }
    }
}
