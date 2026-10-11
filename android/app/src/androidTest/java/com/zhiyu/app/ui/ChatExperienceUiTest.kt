package com.zhiyu.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.ChatTypographyPreferences
import com.zhiyu.app.data.DirectProviderCatalog
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.*
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** Isolated UI fixtures; never authenticates, touches chat databases or calls a provider. */
class ChatExperienceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private lateinit var viewModel: ChatViewModel
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val messages = listOf(
        Message("first", "user", "今天看见一朵花。", emptyList(), "2026-10-09T04:00:00Z"),
        Message("last", "assistant", "是什么颜色呀？", emptyList(), "2026-10-09T04:05:10Z"),
    )

    @Before fun isolatedViewModel() {
        compose.activityRule.scenario.onActivity {
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
        val repository = Proxy.newProxyInstance(ChatRepository::class.java.classLoader,
            arrayOf(ChatRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "savedConnection", "savedBinding" -> null
                "providers" -> DirectProviderCatalog.providers()
                else -> error("UI fixture must not call ${method.name}")
            }
        } as ChatRepository
        compose.runOnUiThread {
            viewModel = ChatViewModel(repository, DocumentReader(context))
            models.put("chat-experience", viewModel)
        }
    }

    @After fun closeViewModel() { compose.runOnUiThread { models.clear() } }

    @Test fun pendingReplyUsesTypingWordsWithoutAnyLoadingIndicatorOrCaption() {
        show(AppState(restoring = false, connected = true, messages = messages,
            pendingMessage = "我想和你分享今天。", busy = "AI 正在输入"))
        compose.onNodeWithTag("companion_typing").assertIsDisplayed()
        compose.onNodeWithText("对方正在输入中").assertIsDisplayed()
        compose.onNodeWithText("同一段聊天，慢慢了解彼此").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
    }

    @Test fun eachKnownMessageHasSecondsAndASeparatedDateAfterTheGap() {
        show(AppState(restoring = false, connected = true, messages = messages))
        compose.onNodeWithTag("message_time_first").assertTextEquals(ChatDisplayRules.messageTime(messages.first().createdAt)!!)
        compose.onNodeWithTag("message_time_last").assertTextEquals(ChatDisplayRules.messageTime(messages.last().createdAt)!!)
        compose.onNodeWithTag("message_gap_last").assertTextEquals(ChatDisplayRules.separator(messages.first().createdAt, messages.last().createdAt)!!)
    }

    @Test fun unknownTimestampDoesNotBecomeNow() {
        compose.setContent { ZhiYuTheme { ChatMessageBubble(messages.first().copy(createdAt = "unknown")) } }
        compose.onNodeWithTag("message_time_first").assertDoesNotExist()
        compose.onNodeWithText(messages.first().content).assertExists()
    }

    @Test fun customFontSizeKeepsSystemAccessibilityScaleAndCompleteOriginal() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f),
                LocalChatTypography provides ChatTypographySettings(22)) {
                ZhiYuTheme { ChatMessageBubble(messages.last()) }
            }
        }
        val node = compose.onNodeWithText(messages.last().content).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
        assertEquals(22.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(1.4f, layouts.single().layoutInput.density.fontScale, 0.001f)
    }

    @Test fun fontSettingsSaveAcrossInstancesWithoutChangingOtherPreferences() {
        val prefix = "test_chat_font_${UUID.randomUUID()}_"
        val testContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(prefix + name, mode)
        }
        val preferences = testContext.getSharedPreferences("chat_typography", Context.MODE_PRIVATE)
        try {
            assertEquals(16, ChatTypographyPreferences(testContext).load().settings.fontSizeSp)
            ChatTypographyPreferences(testContext).save(ChatTypographySettings(24))
            assertEquals(24, ChatTypographyPreferences(testContext).load().settings.fontSizeSp)
            assertTrue(preferences.edit().putString("font_size_sp", "invalid-fixture").commit())
            val invalid = ChatTypographyPreferences(testContext).load()
            assertEquals(16, invalid.settings.fontSizeSp)
            assertNotNull(invalid.warning)
        } finally { assertTrue(preferences.edit().clear().commit()) }
    }

    @Test fun shorterViewportStillShowsTheTailOfAVeryLongMessage() {
        var short by mutableStateOf(false)
        val long = messages.last().copy(content = "一行完整的话。\n".repeat(180) + "最后一句仍然可见。")
        compose.setContent {
            ZhiYuTheme {
                Box(Modifier.width(320.dp).height(if (short) 360.dp else 600.dp)) {
                    ChatScreen(AppState(restoring = false, connected = true, messages = listOf(long)), viewModel,
                        typography = ChatTypographySettings(22))
                }
            }
        }
        compose.onNodeWithTag("message_time_last").assertIsDisplayed()
        compose.runOnIdle { short = true }
        compose.onNodeWithTag("message_time_last").assertIsDisplayed()
        compose.onNodeWithTag("message_draft").assertIsDisplayed()
    }

    @Test fun manuallyReadingEarlierMessagesDoesNotJumpBackDuringAReplyResize() {
        var short by mutableStateOf(false)
        val many = (0..39).map { index ->
            messages.last().copy(id = "manual_$index", content = "我们刚刚聊到第 $index 句。\n这句也完整保存。",
                createdAt = Instant.parse("2026-10-09T04:00:00Z").plusSeconds(index.toLong()).toString())
        }
        compose.setContent {
            ZhiYuTheme {
                Box(Modifier.width(320.dp).height(if (short) 360.dp else 600.dp)) {
                    ChatScreen(AppState(restoring = false, connected = true, messages = many,
                        pendingMessage = "我还想说一句。", busy = "AI 正在输入"), viewModel)
                }
            }
        }
        compose.onNodeWithTag("companion_typing").assertIsDisplayed()
        compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("companion_typing").assertIsNotDisplayed()
        compose.runOnIdle { short = true }
        compose.onNodeWithTag("companion_typing").assertIsNotDisplayed()
        compose.onNodeWithTag("message_time_manual_39").assertIsNotDisplayed()
    }

    @Test fun realKeyboardKeepsRecentMessageAndComposerVisible() {
        var keyboard: SoftwareKeyboardController? = null
        var imeBottom = 0
        val many = (0..24).map { index ->
            messages.last().copy(id = "keyboard_$index", content = "我们说到第 $index 句。",
                createdAt = Instant.parse("2026-10-09T04:00:00Z").plusSeconds(index.toLong()).toString())
        }
        compose.setContent {
            keyboard = LocalSoftwareKeyboardController.current
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { imeBottom = bottom }
            ZhiYuTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        ChatScreen(AppState(restoring = false, connected = true, messages = many), viewModel)
                    }
                }
            }
        }
        compose.onNodeWithTag("message_draft").performClick()
        compose.runOnIdle { keyboard?.show() }
        try {
            compose.waitUntil(8_000) { imeBottom > 0 }
            compose.onNodeWithTag("message_time_keyboard_24").assertIsDisplayed()
            compose.onNodeWithTag("message_draft").assertIsDisplayed()
        } finally { compose.runOnIdle { keyboard?.hide() } }
    }

    private fun show(state: AppState) {
        compose.setContent { ZhiYuTheme { ChatScreen(state, viewModel) } }
    }
}
