package com.zhiyu.app.notifications

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.WorkManager
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ProactiveSchedulePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Scheduling metadata only. Does not read a saved API Key, chat body, microphone or location. */
data class CompanionBackgroundStatus(
    val notificationsAllowed: Boolean,
    val backgroundRestricted: Boolean?,
    val batteryUnrestricted: Boolean?,
    val contactEnabled: Boolean? = null,
    val hasScheduledWork: Boolean? = null,
    val nextCheckAt: Instant? = null,
    val lastOutcome: String? = null,
    val needsRefresh: Boolean = false,
)

enum class CompanionBackgroundSetting { NOTIFICATIONS, APP_DETAILS, BATTERY }

object CompanionBackgroundAccess {
    suspend fun read(context: Context): CompanionBackgroundStatus = withContext(Dispatchers.IO) {
        val app = context.applicationContext ?: context
        val allowed = ProactiveNotifications.allowed(app)
        val restricted = if (Build.VERSION.SDK_INT >= 28) {
            app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        } else false
        val unrestricted = app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)
        val basic = CompanionBackgroundStatus(allowed, restricted, unrestricted)
        try {
            val chat = boundContactChat(app) ?: return@withContext basic
            val store = ProactiveWorkStore(app)
            val manager = WorkManager.getInstance(app)
            val scheduled = listOf(ProactiveScheduler.UNIQUE_NAME, ProactiveSchedulePolicy.uniqueDueName(chat)).any { name ->
                manager.getWorkInfosForUniqueWork(name).get(3, TimeUnit.SECONDS).any { !it.state.isFinished }
            }
            val record = store.lastRun(chat)
            basic.copy(contactEnabled = store.enabled(chat), hasScheduledWork = scheduled,
                nextCheckAt = record?.nextDue, lastOutcome = record?.outcome)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            basic.copy(needsRefresh = true)
        }
    }

    /** Standard user-controlled settings; never requests exemption or opens private OEM activities. */
    fun intent(context: Context, setting: CompanionBackgroundSetting): Intent = when (setting) {
        CompanionBackgroundSetting.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        CompanionBackgroundSetting.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
        CompanionBackgroundSetting.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}

internal fun boundContactChat(context: Context): String? {
    val binding = appCredentialStore(context).readBinding() ?: return null
    if (!context.getDatabasePath("zhiyu_local_v1.sqlite3").isFile) return null
    val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
    return LocalChatDatabase(context).use { it.findConversation(owner) }
        ?.takeIf { it.model == binding.model }?.id
}
