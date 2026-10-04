package com.zhiyu.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** Real emulator → local backend test; start backend -Demo and import demo-guide first. */
class BackendFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun loginChatKnowledgeSourcesAndLogout() {
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("连接后端 · 获取服务商").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("退出并清除本机 Key").performScrollTo().performClick()
            compose.onNodeWithText("退出连接").performClick()
        }
        waitFor("连接后端 · 获取服务商")
        compose.onNodeWithText("连接后端 · 获取服务商").performClick()
        waitFor("DeepSeek")
        compose.onNodeWithTag("provider_selector").performScrollTo().performClick()
        compose.onNodeWithText("本地演示（不调用 AI）").performScrollTo().performClick()
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput("demo-key")
        compose.onNodeWithTag("login").performScrollTo().performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.onNodeWithContentDescription("新对话").performClick()
        waitFor("从一个问题开始")
        compose.onNodeWithTag("message_draft").performTextInput("RAG 支持哪些文件？")
        compose.onNodeWithContentDescription("发送消息").performClick()
        waitFor("条知识引用", substring = true)
        compose.onNodeWithText("条知识引用", substring = true).performScrollTo().performClick()
        waitFor("demo-guide.md")
        compose.onNodeWithText("知识库").performClick()
        waitFor("demo-guide.md")
        compose.onNodeWithText("设置").performClick()
        waitFor("退出并清除本机 Key")
        compose.onNodeWithText("退出并清除本机 Key").performScrollTo().performClick()
        compose.onNodeWithText("退出连接").performClick()
        waitFor("连接后端 · 获取服务商")
        compose.onNodeWithTag("api_key").assertTextEquals("API Key", "")
    }
}
