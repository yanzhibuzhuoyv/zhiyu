package com.zhiyu.app.notifications

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.time.Instant
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ProactiveRunRecord
import org.json.JSONObject

class CompanionBackgroundAccessNativeTest {
    @Test fun diagnosticsShowTheActualLastWorkerCheckAndAnUnresolvedLegacyGrantConflict() = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val binding = CompanionBinding("synthetic", "diagnostic-model")
            SecureCredentialStore(context).writeBinding(binding)
            val owner = "companion-" + LocalMemoryCodec.sha("synthetic\u0000diagnostic-model".toByteArray(Charsets.UTF_8))
            val chat = LocalChatDatabase(context).use { it.ensureConversation(owner, binding.model, null).id }
            val checked = Instant.parse("2026-10-16T15:30:00Z")
            val record = ProactiveRunRecord(checked, "not_due", checked.plusSeconds(3600))
            ProactiveWorkStore(context).recordRun(chat, record)
            check(context.getSharedPreferences("proactive_delivery", 0).edit().putBoolean("enabled_$chat", false).commit())
            val legacy = JSONObject().put("enabled", true).put("start", 22).put("end", 8).put("zone", "Asia/Shanghai")
            check(context.getSharedPreferences("local_proactive", 0).edit().putString(chat, legacy.toString()).commit())
            val status = CompanionBackgroundAccess.read(context)
            assertEquals("Show a real check timestamp, never the screen-open time", checked, status.lastCheckedAt)
            assertEquals(record.nextDue, status.nextCheckAt)
            assertEquals("not_due", status.lastOutcome)
            assertEquals("legacy_conflict", status.migrationIssue)
            assertFalse(status.contactEnabled!!)
        }
    }
    @Test fun systemSettingsRequireOnlyUserNavigationAndUseTheCurrentPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notification = CompanionBackgroundAccess.intent(context, CompanionBackgroundSetting.NOTIFICATIONS)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notification.action)
        assertEquals(context.packageName, notification.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val app = CompanionBackgroundAccess.intent(context, CompanionBackgroundSetting.APP_DETAILS)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.action)
        assertEquals("package:${context.packageName}", app.dataString)
        val battery = CompanionBackgroundAccess.intent(context, CompanionBackgroundSetting.BATTERY)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.action)
        assertNull(battery.component)
        assertNull(battery.data)
        assertNotEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
    }

    @Test fun unboundMetadataCheckDoesNotInventAScheduledContactOrRequestACredential() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val names = mutableSetOf<String>()
        val prefix = "background_status_${UUID.randomUUID()}_"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name
                names.add(scoped)
                return base.getSharedPreferences(scoped, mode)
            }
        }
        try {
            val status = CompanionBackgroundAccess.read(isolated)
            assertNull(status.contactEnabled)
            assertNull(status.hasScheduledWork)
            assertNull(status.nextCheckAt)
            assertNull(status.lastOutcome)
            assertFalse(status.needsRefresh)
            assertEquals(setOf(prefix + "zhiyu_credentials"), names)
        } finally { names.forEach(base::deleteSharedPreferences) }
    }
}
