package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.notifications.CompanionBackgroundSetting
import com.zhiyu.app.notifications.CompanionBackgroundStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AutoBackgroundGuidanceNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "background_guidance_test_${UUID.randomUUID()}"
    private val store = BackgroundGuidanceStore(base, name)
    private var active by mutableStateOf(true)
    private var enabled by mutableStateOf(true)
    private var snapshot = CompanionBackgroundStatus(true, true, false)
    private val opened = mutableListOf<CompanionBackgroundSetting>()
    private var openError: String? = null

    @Before fun focus() { compose.waitUntil(10_000) { compose.activity.hasWindowFocus() } }
    @After fun cleanup() { base.deleteSharedPreferences(name) }

    @Test fun firstRestrictedConnectionExplainsTheDifferenceWithoutAcknowledgingItBeforeAChoice() {
        show()
        awaitGuide()
        compose.onNodeWithTag("background_guidance_dialog").assertExists()
        compose.onNodeWithText("允许通知后，还要留意手机省电设置").assertExists()
        compose.onNodeWithTag("background_guidance_recheck").assertExists()
        assertFalse(store.handled())
        assertTrue(opened.isEmpty())
    }

    @Test fun laterIsPersistedAndReopeningTheConnectedScreenDoesNotPromptAgain() {
        show()
        awaitGuide()
        compose.onNodeWithTag("background_guidance_later").performClick()
        compose.waitUntil(5000) { BackgroundGuidanceStore(base, name).handled() }
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
        assertTrue(opened.isEmpty())
    }

    @Test fun missingNotificationPermissionOrDisabledContactDoesNotLaunchBackgroundGuidance() {
        snapshot = CompanionBackgroundStatus(false, true, false)
        show()
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
        compose.runOnIdle { active = false; snapshot = CompanionBackgroundStatus(true, true, false); enabled = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
        assertFalse(store.handled())
        assertTrue(opened.isEmpty())
    }

    @Test fun anUnrestrictedHealthyConnectionDoesNotNeedAnAutomaticPrompt() {
        snapshot = CompanionBackgroundStatus(true, false, true)
        show()
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
        assertFalse(store.handled())
    }

    @Test fun openingSettingsUsesTheExistingStandardDestinationAndAcknowledgesTheGuide() {
        show()
        awaitGuide()
        compose.onNodeWithTag("background_guidance_settings").performClick()
        compose.waitUntil(5000) { store.handled() }
        assertEquals(listOf(CompanionBackgroundSetting.APP_DETAILS), opened)
        compose.onNodeWithTag("background_guidance_dialog").assertDoesNotExist()
    }

    @Test fun batteryRestrictionUsesTheStandardBatterySettingsInsteadOfAnOemOrGrantRequest() {
        snapshot = CompanionBackgroundStatus(true, false, false)
        show()
        awaitGuide()
        compose.onNodeWithTag("background_guidance_settings").performClick()
        compose.waitUntil(5000) { store.handled() }
        assertEquals(listOf(CompanionBackgroundSetting.BATTERY), opened)
    }

    @Test fun anUnknownStatusOffersAVisibleRecheckAndKeepsTheGuideUntilTheUserChooses() {
        snapshot = CompanionBackgroundStatus(true, null, null, needsRefresh = true)
        show()
        awaitGuide()
        compose.onNodeWithText("后台接收状态暂时无法确认，可以重新检查。").assertExists()
        snapshot = CompanionBackgroundStatus(true, false, true)
        compose.onNodeWithTag("background_guidance_recheck").performClick()
        compose.onNodeWithText("目前没有检测到系统后台限制。").assertExists()
        assertFalse(store.handled())
        assertTrue(opened.isEmpty())
    }

    @Test fun aFailedSettingsNavigationStaysVisibleAndDoesNotPretendTheGuideWasHandled() {
        openError = "系统设置暂时无法打开，请到手机设置中查看知语。"
        show()
        awaitGuide()
        compose.onNodeWithTag("background_guidance_settings").performClick()
        compose.onNodeWithText(checkNotNull(openError)).assertExists()
        assertFalse(store.handled())
        compose.onNodeWithTag("background_guidance_later").assertExists()
    }

    private fun show() {
        compose.setContent {
            AutoBackgroundGuidance(active, enabled, guidanceStore = store,
                statusReader = { snapshot }, settingsOpener = { _, destination -> opened += destination; openError })
        }
    }

    private fun awaitGuide() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("background_guidance_dialog").fetchSemanticsNodes().isNotEmpty()
    }
}
