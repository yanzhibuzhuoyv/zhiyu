package com.zhiyu.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.zhiyu.app.notifications.ProactiveScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CompanionBackgroundCard() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<CompanionBackgroundStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) { status = CompanionBackgroundAccess.read(context) }
    Column(Modifier.fillMaxWidth().testTag("companion_background_status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("离开后也能收到消息", style = MaterialTheme.typography.titleMedium)
        Text(status?.let(::backgroundSummary) ?: "正在检查后台接收设置…", style = MaterialTheme.typography.bodyMedium)
        status?.let { current ->
            val checked = current.lastCheckedAt?.let(::localCheckTime) ?: "还没有后台检查"
            val outcome = current.lastOutcome?.let(::backgroundOutcome)?.let { " · $it" }.orEmpty()
            Text("上次检查：$checked$outcome", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("background_last_check"))
            current.nextCheckAt?.let { due ->
                Text("下次检查安排：${localCheckTime(due)}（系统可能延迟）", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("background_next_check"))
            }
        }
        Text("回到桌面后仍会检查消息；省电可能让消息晚些到。清理最近任务也可能被手机拦截，系统“强行停止”后需要重新打开知语。",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { error = openSettings(context, CompanionBackgroundSetting.NOTIFICATIONS) },
                modifier = Modifier.testTag("background_notification_settings")) { Text("通知设置") }
            OutlinedButton(onClick = { error = openSettings(context, CompanionBackgroundSetting.APP_DETAILS) },
                modifier = Modifier.testTag("background_app_settings")) { Text("后台运行设置") }
        }
        Text("小米 / 红米可在系统应用设置中允许自启动、将省电策略设为无限制；设置名称可能因系统版本不同。",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { error = openSettings(context, CompanionBackgroundSetting.BATTERY) },
                modifier = Modifier.testTag("background_battery_settings")) { Text("电池设置") }
            TextButton(onClick = {
                busy = true
                scope.launch {
                    try { ProactiveScheduler.requestRecovery(context).join(); revision++ }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("background_repair")) { Text("重新检查") }
        }
        if (status?.notificationsAllowed == true && status?.contactEnabled == false) {
            OutlinedButton(onClick = {
                // Start process-owned persistence before screen-bound awaiting/presentation.
                val repair = ProactiveScheduler.restoreContact(context)
                busy = true; error = null
                scope.launch {
                    try { repair.await(); revision++ }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message?.take(160) ?: "主动联系未恢复，请重试。"
                    } finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.testTag("background_restore_contact")) { Text("恢复主动联系") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("background_settings_error")) }
    }
}

private fun backgroundSummary(status: CompanionBackgroundStatus): String = when {
    !status.notificationsAllowed -> "通知还没开启，TA 发来的消息暂时无法提醒你。"
    status.backgroundRestricted == true -> "系统限制了知语的后台运行，请在系统设置中允许后台活动。"
    status.migrationIssue == "legacy_conflict" -> "旧的主动联系设置不一致，已保留关闭。可点“恢复主动联系”。"
    status.contactEnabled == false -> "通知已允许，主动联系目前关闭。你可以点“恢复主动联系”。"
    status.needsRefresh -> "后台接收状态暂时无法读取，可以重新检查。"
    status.contactEnabled == null -> "通知已开启，进入聊天后会检查后台接收安排。"
    status.hasScheduledWork == false && status.contactEnabled == true -> "后台接收安排需要恢复，请点“重新检查”。"
    status.lastOutcome == "needs_connection" -> "上次联系时连接没有成功，后台会继续重试；也可以返回聊天检查连接。"
    status.batteryUnrestricted == false -> "后台消息已安排，手机省电仍可能让提醒晚些到。"
    else -> "后台接收已安排；手机会按人物性格与实际情况来提醒你。"
}

private fun localCheckTime(instant: java.time.Instant): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(instant)

private fun backgroundOutcome(outcome: String): String = when (outcome) {
    "sent" -> "已生成主动消息"
    "not_due" -> "未到联系时机，或仍在等待回复"
    "foreground" -> "当时你在应用内"
    "permission" -> "通知或联系权限未开放"
    "disabled" -> "主动联系已关闭"
    "retry" -> "连接暂未成功，等待重试"
    "needs_connection" -> "需要检查 AI 连接"
    else -> "状态暂时未知"
}

private fun openSettings(context: Context, setting: CompanionBackgroundSetting): String? = try {
    context.startActivity(CompanionBackgroundAccess.intent(context, setting))
    null
} catch (_: ActivityNotFoundException) {
    if (setting == CompanionBackgroundSetting.APP_DETAILS) "系统设置暂时无法打开，请到手机设置中查看知语。"
    else openSettings(context, CompanionBackgroundSetting.APP_DETAILS)
} catch (_: SecurityException) { "系统设置暂时无法打开，请到手机设置中查看知语。" }
