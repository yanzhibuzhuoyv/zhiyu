package com.zhiyu.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zhiyu.app.domain.MemoryEntry
import com.zhiyu.app.ui.screens.MemoryRecognitionInfo
import org.junit.Rule
import org.junit.Test

/** Uses fixed backend metadata; no network, API Key, timing assumptions or queue controls. */
class BackgroundMemoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingIsSortingMemoryWhileChatCanContinue() = show("pending", "正在整理记忆，聊天可以继续")
    @Test fun runningIsSortingMemoryWhileChatCanContinue() = show("running", "正在整理记忆，聊天可以继续")
    @Test fun missingAuthRequestsReconnectionWithoutReportingFailure() = show("waiting_auth", "重新连接后继续整理记忆")

    private fun show(status: String, expected: String) {
        val entry = MemoryEntry("m-1", "user", "原话🍎", "2026-10-06T09:00:00Z", 3,
            "mid", "active", 4, false, "default", null, false, "semantic", status)
        compose.setContent { MaterialTheme { MemoryRecognitionInfo(entry, Modifier.testTag("recognition")) } }
        compose.onNodeWithTag("recognition").assertTextEquals(expected)
        compose.onNodeWithText("语义识别").assertDoesNotExist()
        compose.onNodeWithText("未识别").assertDoesNotExist()
        compose.onNodeWithText("原话已保留，层级可手动确认").assertDoesNotExist()
        compose.onNodeWithText("失败", substring = true).assertDoesNotExist()
    }
}
