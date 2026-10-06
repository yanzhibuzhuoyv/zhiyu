package com.zhiyu.app.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.MainActivity
import com.zhiyu.app.data.SecureCredentialStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Synthetic demo conversations; only this dedicated test installation's local credentials reset. */
class MemoryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureCredentialStore(context)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        context.getSharedPreferences("zhiyu_credentials", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitTag("api_key")
        compose.onNodeWithTag("provider_selector").performScrollTo().performClick()
        compose.onNodeWithText("本地演示（不调用 AI）").performScrollTo().performClick()
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput("memory-ui-${UUID.randomUUID()}")
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_binding").performClick()
        waitText("本地演示 · 无云端 AI 请求")
        waitReady()
    }
    @After fun close() { if (::scenario.isInitialized) scenario.close() }
    private fun waitTag(tag: String) = compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String, substring: Boolean = false) = compose.waitUntil(30000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitReady() = compose.waitUntil(30000) {
        compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun send(text: String) {
        compose.onNodeWithTag("message_draft").performTextInput(text)
        compose.onNodeWithContentDescription("发送消息").performClick()
        waitReady()
    }
    private fun memoryMenu() {
        compose.onNodeWithContentDescription("更多设置").performClick()
        compose.onNodeWithText("记忆").performClick()
        waitTag("memory_dialog")
    }

    @Test fun longPressManagesTierAndArchiveWithoutChangingOriginalOrBinding() {
        val original = "我想吃苹果🍎，这是今天的想法。"
        val binding = store.readBinding()
        send(original)
        compose.onNodeWithText(original).performScrollTo().performTouchInput { longClick() }
        waitTag("memory_original")
        compose.onNodeWithTag("memory_original").assertTextEquals(original)
        compose.onNodeWithTag("memory_semantic_cost").assert(hasText("可能产生额外费用", substring = true))
        compose.onNodeWithTag("memory_recognition").assertTextEquals("未识别")
        compose.onNodeWithText("原话已保留，层级可手动确认").assertExists()
        compose.onNodeWithTag("memory_tier_permanent").performScrollTo().performClick()
        waitText("永久 · 手动指定")
        compose.onNodeWithTag("memory_archive").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("memory_auto").performScrollTo().performClick()
        waitText("中期 · 自动判断")
        compose.onNodeWithTag("memory_archive").performScrollTo().performClick()
        waitText("已归档，原话完整保留")
        compose.onNodeWithTag("memory_original").performScrollTo().assertTextEquals(original)
        compose.onNodeWithTag("memory_restore").performScrollTo().performClick()
        waitText("活跃记忆")
        compose.onNodeWithContentDescription("关闭记忆").performClick()
        compose.onNodeWithText(original).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("新对话").assertDoesNotExist()
        assertEquals(binding, store.readBinding())
    }

    @Test fun memoryMenuKeepsUnicodeExcerptAndArchivedStateAcrossAppReconstruction() {
        val original = "苹果🍎".repeat(400)
        val excerpt = "苹果🍎".repeat(256)
        val binding = store.readBinding()
        send(original)
        memoryMenu()
        waitText(excerpt)
        compose.onNodeWithText(excerpt).performScrollTo().performClick()
        waitTag("memory_original")
        compose.onNodeWithTag("memory_original").assertTextEquals(excerpt)
        compose.onNodeWithText("原文共 1200 字", substring = true).assertExists()
        compose.onNodeWithTag("memory_archive").performScrollTo().performClick()
        waitText("已归档，原话完整保留")
        compose.onNodeWithContentDescription("关闭记忆").performClick()
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitText("本地演示 · 无云端 AI 请求"); waitReady()
        memoryMenu(); waitText(excerpt)
        compose.onNodeWithText(excerpt).performScrollTo().performClick()
        waitTag("memory_original")
        compose.onNodeWithTag("memory_original").assertTextEquals(excerpt)
        compose.onNodeWithTag("memory_index_state").assertTextEquals("已归档，原话完整保留")
        compose.onNodeWithContentDescription("关闭记忆").performClick()
        compose.onNodeWithText(original).performScrollTo().assertExists()
        assertEquals(binding, store.readBinding())
    }
}
