package com.zhiyu.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.CarePreferenceState
import com.zhiyu.app.domain.CareRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Stable
class CareController(
    context: Context, private val scope: CoroutineScope,
    private val weatherClient: NativeWeatherService = NativeWeatherClient(),
    private val location: CareLocationSource = ForegroundLocationSource(context),
    private val locationAllowed: () -> Boolean = {
        ContextCompat.checkSelfPermission(context.applicationContext ?: context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    },
) {
    private val appContext = context.applicationContext ?: context
    private val store = CarePreferences(appContext)
    private val mutex = Mutex()
    private var generation = 0
    private var refreshJob: Job? = null
    var state by mutableStateOf(CarePreferenceState())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set

    fun allowed() = locationAllowed()

    fun restore() = scope.launch {
        val loaded = withContext(Dispatchers.IO) { store.load() }
        state = loaded.state
        error = loaded.warning
        if (state.enabled && !allowed()) disable("大致位置权限已关闭，天气关心已暂停。聊天仍可正常使用。")
        else refreshIfDue()
        ready = true
    }

    fun enable() {
        if (!allowed()) { permissionDenied(); return }
        state = state.copy(enabled = true)
        refresh(activate = true)
    }

    fun disable(message: String? = null) {
        val disabledGeneration = ++generation
        refreshJob?.cancel()
        busy = false
        state = CarePreferenceState()
        error = null
        notice = message
        scope.launch {
            try { mutex.withLock {
                if (generation == disabledGeneration) withContext(Dispatchers.IO) { store.save(CarePreferenceState()) }
            } }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message?.take(300) ?: "天气设置没有保存成功，请重试"
            }
        }
    }

    fun refresh() = refresh(activate = false)
    private fun refresh(activate: Boolean) {
        if (!state.enabled || !allowed()) { permissionDenied(); return }
        refreshJob?.cancel()
        val requestGeneration = ++generation
        refreshJob = scope.launch {
            busy = true
            error = null
            notice = null
            try {
                mutex.withLock {
                    if (requestGeneration != generation || !state.enabled) return@launch
                    val persisted = withContext(Dispatchers.IO) {
                        if (activate) store.setEnabled(true) else store.load().state
                    }
                    state = persisted
                    if (!persisted.enabled) return@launch
                }
                val snapshot = weatherClient.fetch(location.current())
                ensureActive()
                mutex.withLock {
                    if (requestGeneration != generation || !state.enabled) return@withLock
                    if (!allowed()) { disable("定位权限已关闭，天气关心已暂停。"); return@withLock }
                    val accepted = withContext(Dispatchers.IO) {
                        store.publishWeatherIfEnabled(CareRules.weather(snapshot), System.currentTimeMillis() / 1000, ::allowed)
                        store.load().state
                    }
                    if (requestGeneration == generation) state = accepted
                }
            } catch (_: TimeoutCancellationException) {
                if (requestGeneration == generation) error = "暂时没能取得位置，聊天仍可使用。稍后再刷新天气。"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (requestGeneration == generation) error = failure.message?.take(300) ?: "天气获取失败，请稍后重试"
            } finally { if (requestGeneration == generation) busy = false }
        }
    }

    fun onResume() {
        if (state.enabled && !allowed()) disable("定位权限已关闭，天气关心已暂停。")
        else refreshIfDue()
    }

    fun cancelPendingLocation() {
        generation++
        refreshJob?.cancel()
        busy = false
    }

    fun permissionDenied() { error = "没有允许大致位置，天气关心未开启。聊天仍可正常使用。" }
    fun reportError(message: String) { error = message.take(300); notice = null }
    fun calendarOpened() { error = null; notice = "已交给系统日历，请在那里确认保存。" }
    fun dismissFeedback() { error = null; notice = null }

    internal fun refreshIfDue() {
        val last = state.weather?.fetchedAt ?: 0L
        if (state.enabled && !busy && System.currentTimeMillis() / 1000 - last >= CareRules.REFRESH_INTERVAL_SECONDS) refresh()
    }
}

@Composable fun rememberCareController(): CareController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context.applicationContext, scope) { CareController(context, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) { controller.restore() }
    LaunchedEffect(controller, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { controller.refreshIfDue(); delay(60_000) }
        }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> controller.cancelPendingLocation()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.cancelPendingLocation() }
    }
    return controller
}

@Composable fun rememberCareToggle(controller: CareController): (Boolean) -> Unit {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && controller.allowed()) controller.enable() else controller.permissionDenied()
    }
    return { enabled ->
        when {
            !enabled -> controller.disable()
            controller.allowed() -> controller.enable()
            else -> permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
