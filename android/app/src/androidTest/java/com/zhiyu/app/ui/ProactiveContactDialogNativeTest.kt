package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zhiyu.app.domain.ProactiveCheck
import com.zhiyu.app.domain.ProactivePreferences
import com.zhiyu.app.domain.ProactiveService
import com.zhiyu.app.ui.screens.ProactiveContactDialog
import org.junit.Rule
import org.junit.Test

/** Presentation must match the existing policy; no settings write, Key or provider call. */
class ProactiveContactDialogNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val service = object : ProactiveService {
        override suspend fun preferences(id: String): ProactivePreferences = error("No settings read")
        override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences = error("No settings write")
        override suspend fun check(id: String, requestId: String): ProactiveCheck = error("No provider call")
    }

    @Test fun contactSettingsDoNotInventALegacyNightQuietWindow() {
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val controller = remember(context, scope) { ProactiveController(context, null, scope, service) }
            ProactiveContactDialog(controller, {}, {})
        }
        compose.onNodeWithText("不设固定的夜间静默时段，TA 会按自己的性格和最近的聊天情况联系你。").assertExists()
        compose.onNodeWithText("每天 22:00—08:00 保持安静（Asia/Shanghai）。").assertDoesNotExist()
    }
}
