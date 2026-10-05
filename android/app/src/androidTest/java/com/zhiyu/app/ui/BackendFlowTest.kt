package com.zhiyu.app.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.MainActivity
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.CompanionBinding
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Demo emulator integration; this fixture resets only this test installation's local credentials. */
class BackendFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureCredentialStore(context)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launchFreshTestInstallation() {
        context.getSharedPreferences("zhiyu_credentials", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("api_key")
    }
    @After fun closeActivity() { if (::scenario.isInitialized) scenario.close() }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForTag(tag: String) {
        compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun chooseDemoAndEnterKey(key: String = "demo-key") {
        compose.onNodeWithTag("provider_selector").performScrollTo().performClick()
        compose.onNodeWithText("本地演示（不调用 AI）").performScrollTo().performClick()
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput(key)
    }
    private fun confirmDemoBinding() {
        chooseDemoAndEnterKey()
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_binding").performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun menu(item: String) {
        compose.onNodeWithContentDescription("更多设置").performClick()
        compose.onNodeWithText(item).performClick()
    }

    @Test fun uniqueChoiceRequiresConfirmationAndCancellationDoesNotBind() {
        compose.onNodeWithText("恋人仅此唯一，此择不可挽回").assertExists()
        compose.onNodeWithTag("server_url").assertDoesNotExist()
        chooseDemoAndEnterKey()
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithText("确认唯一的选择").assertExists()
        compose.onNodeWithText("AI 公司：本地演示（不调用 AI）", substring = true).assertExists()
        compose.onNodeWithText("模型：", substring = true).assertExists()
        assertNull(store.readBinding())
        assertNull(store.read())
        compose.onNodeWithText("再想想").performClick()
        compose.onNodeWithTag("confirm_binding").assertDoesNotExist()
        compose.onNodeWithTag("provider_selector").assertExists()
        assertNull(store.readBinding())
        assertNull(store.read())
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_binding").performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        assertEquals("demo", store.readBinding()?.providerId)
        assertTrue(store.readBinding()?.model?.isNotBlank() == true)
    }

    @Test fun singleChatSourcesAndRevalidationRetainTheBinding() {
        confirmDemoBinding()
        val binding = store.readBinding()
        assertNotNull(binding)
        compose.onNodeWithContentDescription("新对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("会话列表").assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.onNodeWithTag("rag_toggle").assertDoesNotExist()
        compose.onNodeWithTag("message_draft").performTextInput("RAG 支持哪些文件？")
        compose.onNodeWithContentDescription("发送消息").performClick()
        waitFor("条知识引用", substring = true)
        compose.onAllNodesWithText("条知识引用", substring = true).onLast().performScrollTo().performClick()
        waitFor("demo-guide.md")
        menu("资料")
        compose.onNodeWithTag("library_dialog").assertExists()
        compose.onNode(hasText("demo-guide.md") and hasAnyAncestor(hasTestTag("library_dialog"))).assertExists()
        compose.onNodeWithContentDescription("关闭资料").performClick()
        compose.onNodeWithTag("library_dialog").assertDoesNotExist()
        compose.onNodeWithTag("message_draft").assertExists()
        menu("重新验证 API Key")
        compose.onNodeWithText("仍会保留，不能更换", substring = true).assertExists()
        compose.onNodeWithTag("confirm_revalidate").performClick()
        waitForTag("api_key")
        compose.onNodeWithText("恋人仅此唯一，此择不可挽回").assertExists()
        compose.onNodeWithText("已绑定的 AI").assertExists()
        compose.onNodeWithTag("api_key").assertTextEquals("API Key", "")
        compose.onNodeWithTag("provider_selector").assertDoesNotExist()
        compose.onNodeWithTag("model_id").assertDoesNotExist()
        compose.onNodeWithText("模型设置（可选）").assertDoesNotExist()
        assertEquals(binding, store.readBinding())
        assertNull(store.read())
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput("demo-key")
        compose.onNodeWithTag("login").performScrollTo().performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.onNodeWithTag("confirm_binding").assertDoesNotExist()
        assertEquals(binding, store.readBinding())
    }

    @Test fun developerSettingsCancelPreservesDraftKeyAndBinding() {
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput("key-draft")
        compose.onNodeWithContentDescription("开发设置").performScrollTo().performClick()
        compose.onNodeWithTag("server_url").assertExists()
        compose.onNodeWithTag("server_url").performTextReplacement("http://10.0.2.2:9999")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("server_url").assertDoesNotExist()
        compose.onNodeWithContentDescription("显示 Key").performClick()
        compose.onNodeWithTag("api_key").assertTextEquals("API Key", "key-draft")
        compose.onNodeWithContentDescription("隐藏 Key").performClick()
        assertNull(store.readBinding())
        compose.onNodeWithTag("api_key").performTextClearance()
        confirmDemoBinding()
        val saved = store.read()
        val binding: CompanionBinding? = store.readBinding()
        menu("开发设置")
        compose.onNodeWithTag("server_url").performTextReplacement("http://10.0.2.2:9999")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("message_draft").assertExists()
        assertEquals(saved, store.read())
        assertEquals(binding, store.readBinding())
    }

    @Test fun presetLoverProfileBecomesReadOnlyAfterBinding() {
        chooseDemoAndEnterKey("ui-preset-${UUID.randomUUID()}")
        compose.onNodeWithText("恋人设定（可选）").performScrollTo().performClick()
        compose.onNodeWithTag("profile_mode_preset").performClick()
        compose.onNodeWithTag("style_playful").performScrollTo().performClick()
        compose.onNodeWithTag("role_girlfriend").performScrollTo().performClick()
        compose.onNodeWithTag("companion_name").performScrollTo().performTextInput("知心")
        compose.onNodeWithTag("user_name").performScrollTo().performTextInput("小语")
        compose.onNodeWithTag("save_profile").performClick()
        waitFor("已选择：俏皮亲昵 · 女友")
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithText("这份恋人预设将同时固定，之后不能修改。").assertExists()
        compose.onNodeWithText("性格：俏皮亲昵", substring = true).assertExists()
        compose.onNodeWithText("恋人形象：女友", substring = true).assertExists()
        compose.onNodeWithTag("confirm_binding").performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val binding = store.readBinding()!!
        assertEquals("preset", binding.profile?.mode)
        assertEquals("playful", binding.profile?.style)
        assertEquals("girlfriend", binding.profile?.role)
        menu("恋人设定")
        compose.onNodeWithTag("profile_locked").assertExists()
        compose.onNodeWithText("已固定的恋人设定").assertExists()
        compose.onNodeWithTag("save_profile").assertDoesNotExist()
        compose.onNodeWithTag("profile_mode_preset").assertDoesNotExist()
        compose.onNodeWithTag("companion_name").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        assertEquals(binding, store.readBinding())
    }

    @Test fun customLoverSetupCanBeEditedAndCancelDoesNotSave() {
        chooseDemoAndEnterKey("ui-custom-${UUID.randomUUID()}")
        compose.onNodeWithText("恋人设定（可选）").performScrollTo().performClick()
        compose.onNodeWithTag("profile_enable").performClick()
        compose.onNodeWithTag("companion_name").performScrollTo().performTextInput("知心")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("profile_summary").assertDoesNotExist()
        compose.onNodeWithText("恋人设定（可选）").performScrollTo().performClick()
        compose.onNodeWithTag("profile_mode_custom").assertIsSelected()
        compose.onNodeWithTag("profile_enable").assertIsOff()
        compose.onNodeWithTag("profile_enable").performClick()
        compose.onNodeWithTag("companion_name").performScrollTo().performTextInput("知心")
        compose.onNodeWithTag("user_name").performScrollTo().performTextInput("小语")
        compose.onNodeWithTag("profile_instructions").performScrollTo().performTextInput("说话温柔，回应简短些。")
        compose.onNodeWithTag("save_profile").performClick()
        waitFor("已选择：自行调教")
        assertNull(store.readBinding())
        compose.onNodeWithTag("login").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_binding").performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val binding = store.readBinding()!!
        menu("恋人设定")
        compose.onNodeWithTag("profile_mode_preset").assertDoesNotExist()
        compose.onNodeWithTag("profile_enable").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("profile_instructions").performScrollTo().performTextReplacement("请用活泼的语气回应。")
        compose.onNodeWithText("取消").performClick()
        menu("恋人设定")
        compose.onNodeWithTag("profile_instructions").performScrollTo().assert(hasText("说话温柔，回应简短些。"))
        compose.onNodeWithTag("profile_instructions").performTextReplacement("请用活泼的语气回应。")
        compose.onNodeWithTag("save_profile").performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        menu("恋人设定")
        compose.onNodeWithTag("profile_instructions").performScrollTo().assert(hasText("请用活泼的语气回应。"))
        compose.onNodeWithTag("profile_mode_preset").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        assertEquals(binding.providerId, store.readBinding()?.providerId)
        assertEquals(binding.model, store.readBinding()?.model)
        assertEquals("请用活泼的语气回应。", store.readBinding()?.profile?.instructions)
        val currentKey = store.read()!!.apiKey
        menu("重新验证 API Key")
        compose.onNodeWithTag("confirm_revalidate").performClick()
        waitForTag("api_key")
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag("api_key")
        compose.onNodeWithTag("api_key").performScrollTo().performTextInput(currentKey)
        compose.onNodeWithTag("login").performScrollTo().performClick()
        waitFor("本地演示 · 无云端 AI 请求")
        compose.waitUntil(30000) { compose.onAllNodes(hasContentDescription("更多设置") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        menu("恋人设定")
        compose.onNodeWithTag("profile_instructions").performScrollTo().assert(hasText("请用活泼的语气回应。"))
        compose.onNodeWithText("取消").performClick()
    }
}
