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

class CompanionBackgroundAccessNativeTest {
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
