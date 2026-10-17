package com.zhiyu.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhiyu.app.notifications.CompanionBackgroundAccess
import com.zhiyu.app.notifications.CompanionBackgroundSetting
import com.zhiyu.app.notifications.CompanionBackgroundStatus
import com.zhiyu.app.notifications.ProactiveNotifications
import kotlinx.coroutines.*

/** App onboarding, separate from lover dialogue; Android remains in control of background policy. */
@Composable
fun AutoBackgroundGuidance(
    active: Boolean,
    contactEnabled: Boolean,
    guidanceStore: BackgroundGuidanceStore? = null,
    statusReader: (suspend (Context) -> CompanionBackgroundStatus)? = null,
    settingsOpener: ((Context, CompanionBackgroundSetting) -> String?)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(context.applicationContext, guidanceStore) { guidanceStore ?: BackgroundGuidanceStore(context) }
    val reader by rememberUpdatedState(statusReader)
    val opener by rememberUpdatedState(settingsOpener)
    val host = remember(context, store, scope) { BackgroundGuidanceHost(context, store, scope,
        { reader?.invoke(it) ?: CompanionBackgroundAccess.read(it) },
        { destination -> val current = opener
            if (current != null) current(context, destination) else openBackgroundSettings(context, destination) }) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active, contactEnabled, resumed, revision, host) { host.refresh(active && resumed, contactEnabled) }
    if (host.visible && active && contactEnabled && resumed) BackgroundGuidanceDialog(host,
        recheck = { scope.launch { host.refresh(active && resumed, contactEnabled) } })
}

@Stable private class BackgroundGuidanceHost(
    private val context: Context,
    private val store: BackgroundGuidanceStore,
    private val scope: CoroutineScope,
    private val read: suspend (Context) -> CompanionBackgroundStatus,
    private val open: (CompanionBackgroundSetting) -> String?,
) {
    var visible by mutableStateOf(false); private set
    var status by mutableStateOf<CompanionBackgroundStatus?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var handled: Boolean? = null
    private var returningFromSettings = false
    private var saveFailed = false

    suspend fun refresh(active: Boolean, enabled: Boolean) {
        if (!active || !enabled) { visible = false; return }
        if (busy) return
        busy = true
        try {
            if (handled == null) handled = withContext(Dispatchers.IO) { store.handled() }
            if (handled == true && !returningFromSettings) return
            val current = read(context)
            status = current
            error = null
            returningFromSettings = false
            visible = handled != true && current.notificationsAllowed && (visible ||
                current.backgroundRestricted != false || current.batteryUnrestricted != true || current.needsRefresh)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            status = CompanionBackgroundStatus(ProactiveNotifications.allowed(context), null, null, needsRefresh = true)
            error = "后台接收状态暂时无法读取。聊天仍可使用，也可以重新检查。"
            visible = handled != true && status?.notificationsAllowed == true
        } finally { busy = false }
    }

    fun acknowledge(destination: CompanionBackgroundSetting? = null) {
        if (busy) return
        if (saveFailed && destination == null) { handled = true; visible = false; return }
        busy = true
        scope.launch {
            try {
                if (destination != null) {
                    val failure = open(destination)
                    if (failure != null) { error = failure; return@launch }
                    returningFromSettings = true
                }
                try { withContext(NonCancellable + Dispatchers.IO) { store.markHandled() } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    saveFailed = true
                    error = "说明状态没能保存；可先关闭，下一次打开时可能再次显示。"
                    return@launch
                }
                handled = true
                visible = false
            } finally { busy = false }
        }
    }
}

@Composable private fun BackgroundGuidanceDialog(host: BackgroundGuidanceHost, recheck: () -> Unit) {
    val status = host.status
    AlertDialog(onDismissRequest = { host.acknowledge() }, modifier = Modifier.testTag("background_guidance_dialog"),
        title = { Text("允许通知后，还要留意手机省电设置") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                status == null || status.needsRefresh || status.backgroundRestricted == null || status.batteryUnrestricted == null ->
                    "后台接收状态暂时无法确认，可以重新检查。"
                status.backgroundRestricted == true -> "系统限制了知语的后台运行。即使允许了通知，离开后也可能收不到主动消息。"
                status.batteryUnrestricted == false -> "手机省电可能延迟后台消息，可以在系统设置中检查知语的电池策略。"
                else -> "目前没有检测到系统后台限制。"
            })
            Text("这里是知语的接收设置说明，不会替你更改系统设置。后台限制可能让消息延迟；强行停止知语后需重新打开。")
            Text("稍后也可从“更多 → 日历 → 离开后也能收到消息”重新检查。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = recheck, enabled = !host.busy, modifier = Modifier.testTag("background_guidance_recheck")) { Text("重新检查") }
            host.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { host.acknowledge(if (status?.backgroundRestricted == true)
            CompanionBackgroundSetting.APP_DETAILS else if (status?.batteryUnrestricted == false)
            CompanionBackgroundSetting.BATTERY else CompanionBackgroundSetting.APP_DETAILS) },
            enabled = !host.busy, modifier = Modifier.testTag("background_guidance_settings")) { Text("去后台设置") } },
        dismissButton = { TextButton(onClick = { host.acknowledge() }, enabled = !host.busy,
            modifier = Modifier.testTag("background_guidance_later")) { Text("稍后") } })
}

private fun openBackgroundSettings(context: Context, destination: CompanionBackgroundSetting): String? = try {
    context.startActivity(CompanionBackgroundAccess.intent(context, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (_: ActivityNotFoundException) {
    if (destination != CompanionBackgroundSetting.APP_DETAILS) openBackgroundSettings(context, CompanionBackgroundSetting.APP_DETAILS)
    else "系统设置暂时无法打开，请到手机设置中查看知语。"
} catch (_: SecurityException) { "系统设置暂时无法打开，请到手机设置中查看知语。" }
