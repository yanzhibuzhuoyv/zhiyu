package com.zhiyu.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.data.ProactiveRepository
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.domain.UsbDebugCredentialStore
import com.zhiyu.app.notifications.ProactiveNotifications
import com.zhiyu.app.notifications.ProactiveScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
class ProactiveController(context: Context, private val conversationId: String?, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = ProactiveWorkStore(appContext)
    private val service = ProactiveRepository(UsbDebugCredentialStore(SecureCredentialStore(appContext), BuildConfig.DEBUG && BuildConfig.USE_USB_BACKEND), BuildConfig.DEBUG)
    private val mutex = Mutex()
    var preferences by mutableStateOf(ProactivePreferences())
        private set
    var busy by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun restore() = operation {
        val id = conversationId ?: return@operation
        val remote = service.preferences(id)
        val locallyEnabled = withContext(Dispatchers.IO) { store.enabled(id) }
        preferences = remote.copy(enabled = remote.enabled && locallyEnabled && ProactiveNotifications.allowed(appContext))
        if (preferences.enabled) ProactiveScheduler.schedule(appContext, id)
        else ProactiveScheduler.cancel(appContext)
        ready = true
    }

    fun toggle(enabled: Boolean) = operation {
        val id = conversationId ?: throw IllegalStateException("请先进入聊天")
        if (enabled && !ProactiveNotifications.allowed(appContext)) { permissionDenied(); return@operation }
        if (!enabled) {
            withContext(Dispatchers.IO) { store.setEnabled(id, false) }
            ProactiveScheduler.cancel(appContext)
            preferences = preferences.copy(enabled = false)
        }
        val accepted = service.savePreferences(id, preferences.copy(enabled = enabled))
        withContext(Dispatchers.IO) { store.setEnabled(id, accepted.enabled) }
        preferences = accepted
        if (accepted.enabled) ProactiveScheduler.schedule(appContext, id) else ProactiveScheduler.cancel(appContext)
    }

    fun permissionDenied() { error = "通知没有开启，聊天仍可正常使用。允许知语通知后，可以再开启主动联系。" }
    fun dismissError() { error = null }

    private fun operation(block: suspend () -> Unit) = scope.launch {
        mutex.withLock {
            busy = true
            error = null
            try { block() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message?.take(300) ?: "主动联系设置失败，请稍后再试。"
            } finally { busy = false }
        }
    }
}

@Composable fun rememberProactiveController(conversationId: String?, connected: Boolean): ProactiveController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context.applicationContext, conversationId, scope) { ProactiveController(context, conversationId, scope) }
    LaunchedEffect(controller, connected) { if (connected && conversationId != null) controller.restore() }
    return controller
}

@Composable fun rememberProactiveToggle(controller: ProactiveController): (Boolean) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && ProactiveNotifications.allowed(context)) controller.toggle(true) else controller.permissionDenied()
    }
    return { enabled ->
        when {
            !enabled -> controller.toggle(false)
            ProactiveNotifications.allowed(context) -> controller.toggle(true)
            Build.VERSION.SDK_INT >= 33 -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> controller.permissionDenied()
        }
    }
}
