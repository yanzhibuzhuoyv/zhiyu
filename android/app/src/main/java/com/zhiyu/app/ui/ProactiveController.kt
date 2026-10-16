package com.zhiyu.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.zhiyu.app.BuildConfig
import com.zhiyu.app.data.LocalProactiveService
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.notifications.ProactiveNotifications
import com.zhiyu.app.notifications.ProactiveScheduler
import com.zhiyu.app.notifications.ContactPreferenceWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
class ProactiveController(context: Context, private val conversationId: String?, private val scope: CoroutineScope,
    private val service: com.zhiyu.app.domain.ProactiveService = LocalProactiveService(context, appCredentialStore(context))) {
    private val appContext = context.applicationContext
    private val store = ProactiveWorkStore(appContext)
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

    fun toggle(enabled: Boolean): Job {
        val id = conversationId
        val allowed = !enabled || ProactiveNotifications.allowed(appContext)
        // Start the local commit before launching screen-bound presentation work. Leaving
        // the Activity can cancel await/UI updates, but not a notification grant's settings.
        val commit = if (id != null && allowed) ContactPreferenceWriter.submit(appContext, id,
            preferences.copy(enabled = enabled), service) else null
        return operation {
            if (id == null) throw IllegalStateException("请先进入聊天")
            if (!allowed) { permissionDenied(); return@operation }
            if (!enabled) preferences = preferences.copy(enabled = false)
            preferences = requireNotNull(commit).await()
        }
    }

    fun permissionDenied() { error = "通知没有开启，聊天仍可正常使用。你可以在系统设置中允许知语通知。" }
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
