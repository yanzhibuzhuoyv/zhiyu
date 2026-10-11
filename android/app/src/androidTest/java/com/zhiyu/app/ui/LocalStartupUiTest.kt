package com.zhiyu.app.ui

import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File

/** Explicitly run with airplane mode on, in the newly created empty test AVD. */
class LocalStartupUiTest {
    @get:Rule(order = 0) val guard = object : ExternalResource() {
        override fun before() {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(!context.getSharedPreferences("zhiyu_credentials", 0).contains("encrypted")) {
                "This test requires a fresh empty emulator; it never clears saved credentials"
            }
            assertEquals(1, android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun companiesAndKeyScreenOpenWithAllNetworkDisabled() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("provider_selector").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("partner_choice_motto").assertTextEquals("恋人仅此唯一，此择不可挽回")
        compose.onNodeWithTag("api_key").assertExists()
        compose.onNodeWithTag("provider_selector").performClick()
        compose.onAllNodesWithText("DeepSeek").assertCountEquals(2)
        compose.onNodeWithText("通义千问", substring = true).assertExists()
        compose.onNodeWithText("MiniMax").assertExists()
        compose.onNodeWithText("后端地址", substring = true).assertDoesNotExist()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "local-offline-company-picker.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        com.zhiyu.app.data.nativeProbeReport("LOCAL_STARTUP_UI_REPORT airplane_mode=true company_catalog=passed pc_backend=false")
    }
}
