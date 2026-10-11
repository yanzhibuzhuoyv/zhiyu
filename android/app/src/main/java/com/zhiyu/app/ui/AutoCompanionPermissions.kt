package com.zhiyu.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.data.CompanionCalendarStore
import com.zhiyu.app.data.CompanionPermissionRequests
import com.zhiyu.app.domain.CompanionPermission
import com.zhiyu.app.domain.CompanionPermissionPolicy
import com.zhiyu.app.notifications.ProactiveNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** No settings switch or repeated denial loop: Android's permission decision enables each capability. */
@Composable
fun AutoCompanionPermissions(active: Boolean, proactive: ProactiveController, care: CareController) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requests = remember(context) { CompanionPermissionRequests(context) }
    val calendar = remember(context) { CompanionCalendarStore(context) }
    val stillActive by rememberUpdatedState(active)
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val state = remember(requests, lifecycle, pending) { PermissionHostState(lifecycle, pending) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.inFlight = null
        state.revision++
    }
    LaunchedEffect(requests) {
        try { state.requested = withContext(Dispatchers.IO) { requests.requested() }; state.loaded = true }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            care.reportError("权限申请状态暂时无法读取，聊天仍可使用。")
        }
    }
    ObservePermissionLifecycle(lifecycle, state)
    LaunchedEffect(active, state.resumed, state.loaded, state.revision, proactive.ready, care.ready) {
        if (!active || !state.resumed || !state.loaded || state.inFlight != null || !care.ready || !proactive.ready) return@LaunchedEffect
        val granted = grantedCapabilities(context, care, calendar)
        try {
            enableGrantedCapabilities(granted, state, requests, proactive, care, calendar)
            requestNextCapability(state, requests, granted, lifecycle, { stillActive }, launcher)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            state.inFlight = null
            care.reportError("这次没能完成权限申请，聊天仍可使用。")
        }
    }
}

@Stable private class PermissionHostState(lifecycle: Lifecycle, private val pending: MutableState<String?>) {
    var requested by mutableStateOf(emptySet<CompanionPermission>())
    var loaded by mutableStateOf(false)
    var inFlight: CompanionPermission?
        get() = pending.value?.let { CompanionPermission.valueOf(it) }
        set(value) { pending.value = value?.name }
    var blocked by mutableStateOf(emptySet<CompanionPermission>())
    var revision by mutableStateOf(0)
    var resumed by mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
}

@Composable private fun ObservePermissionLifecycle(lifecycle: Lifecycle, state: PermissionHostState) {
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            state.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) state.revision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

private fun grantedCapabilities(context: Context, care: CareController,
    calendar: CompanionCalendarStore): Set<CompanionPermission> = buildSet {
    if (ProactiveNotifications.allowed(context)) add(CompanionPermission.CONTACT)
    if (care.allowed()) add(CompanionPermission.WEATHER)
    if (calendar.allowed()) add(CompanionPermission.CALENDAR)
}

private suspend fun enableGrantedCapabilities(granted: Set<CompanionPermission>, state: PermissionHostState,
    requests: CompanionPermissionRequests, proactive: ProactiveController, care: CareController, calendar: CompanionCalendarStore) {
    // Treat existing grants as handled too, so a later manual revocation is not re-prompted.
    granted.filter { it !in state.requested }.forEach { permission ->
        withContext(Dispatchers.IO) { requests.markRequested(permission) }
        state.requested = state.requested + permission
    }
    if (proactive.preferences.enabled != (CompanionPermission.CONTACT in granted)) {
        proactive.toggle(CompanionPermission.CONTACT in granted)
    }
    if (CompanionPermission.WEATHER in granted && !care.state.enabled) care.enable()
    if (CompanionPermission.WEATHER !in granted && care.state.enabled) care.disable()
    withContext(Dispatchers.IO) { calendar.setEnabled(CompanionPermission.CALENDAR in granted) }
}

private suspend fun requestNextCapability(state: PermissionHostState, requests: CompanionPermissionRequests,
    granted: Set<CompanionPermission>, lifecycle: Lifecycle, stillActive: () -> Boolean, launcher: ActivityResultLauncher<Array<String>>) {
    val next = CompanionPermissionPolicy.next(state.requested, granted, state.blocked) ?: return
    if (!stillActive() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    // Older Android releases have no notification runtime dialog; system settings remain authoritative.
    if (next == CompanionPermission.CONTACT && Build.VERSION.SDK_INT < 33) {
        withContext(Dispatchers.IO) { requests.markRequested(next) }
        state.requested = state.requested + next; state.revision++; return
    }
    var recorded = false
    var launched = false
    try {
        withContext(NonCancellable + Dispatchers.IO) { requests.markRequested(next); recorded = true }
        if (!stillActive() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        state.inFlight = next
        launcher.launch(next.permissions())
        launched = true
    } catch (failure: Exception) {
        if (failure !is CancellationException) state.blocked = state.blocked + next
        throw failure
    } finally {
        if (!launched) {
            if (recorded) withContext(NonCancellable + Dispatchers.IO) { requests.clearRequested(next) }
            state.inFlight = null
        }
        state.requested = CompanionPermissionPolicy.afterAttempt(state.requested, next, launched)
    }
}

private fun CompanionPermission.permissions(): Array<String> = when (this) {
    CompanionPermission.CONTACT -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    CompanionPermission.WEATHER -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    CompanionPermission.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
}
