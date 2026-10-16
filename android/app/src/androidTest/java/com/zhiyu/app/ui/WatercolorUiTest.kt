package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.File
import java.lang.reflect.Proxy

/** Synthetic UI only: no saved keys, binding, application database or provider calls. */
@Suppress("DEPRECATION")
class WatercolorUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var viewModel: ChatViewModel
    private val models = ViewModelStore()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val providers = DirectProviderCatalog.providers()
    private val originals = listOf(
        Message("watercolor_user", "user", "今天路边的花开了，我一下就想到了你。", emptyList(), "2026-10-09T10:00:00Z"),
        Message("watercolor_companion", "assistant", "那就把这朵花分我一半吧。\n你慢慢说，我听着。", emptyList(), "2026-10-09T10:00:01Z"),
    )

    @Before fun createIsolatedViewModel() {
        val repository = Proxy.newProxyInstance(ChatRepository::class.java.classLoader,
            arrayOf(ChatRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "savedConnection", "savedBinding" -> null
                "providers" -> providers
                else -> error("UI fixture must not call repository method ${method.name}")
            }
        } as ChatRepository
        compose.runOnUiThread {
            viewModel = ChatViewModel(repository, DocumentReader(context))
            models.put("watercolor", viewModel)
        }
    }

    @After fun disposeViewModel() { compose.runOnUiThread { models.clear() } }

    @Test fun narrowLargeTextLoginStillReachesKeyAndBindingConfirmation() {
        val state = AppState(restoring = false, providers = providers, providerId = "deepseek",
            model = providers.first().defaultModel, apiKey = "synthetic-not-a-live-key",
            profile = CompanionProfile(style = "warm_v2", name = "知语", userName = "小语"))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                ZhiYuTheme(darkTheme = false) { Box(Modifier.width(320.dp).fillMaxHeight()) { ConnectionScreen(state, viewModel) } }
            }
        }
        compose.onNodeWithTag("welcome_watercolor_art").assertExists()
        compose.onNodeWithTag("api_key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("显示 Key").assertExists()
        compose.onNodeWithTag("login").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("确认唯一的选择").assertExists()
        compose.onNodeWithText("再想想").performClick()
        compose.onNodeWithTag("confirm_binding").assertDoesNotExist()
    }

    @Test fun shortLargeTextEmptyChatCanScrollToTheSuggestion() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                ZhiYuTheme(darkTheme = false) {
                    Box(Modifier.width(320.dp).height(360.dp)) {
                        ChatScreen(AppState(restoring = false, connected = true), viewModel)
                    }
                }
            }
        }
        compose.onNodeWithText("先聊聊今天").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("message_draft").assertIsDisplayed()
        compose.runOnIdle { assertEquals("今天我想和你分享一件事。", viewModel.state.value.draft) }
    }

    @Test fun lightAndDarkChatKeepIndependentOriginalsAndReadableControls() {
        // Instrumentation arg lets the same native screen be inspected in both palettes.
        val dark = InstrumentationRegistry.getArguments().getString("watercolorDark") == "true"
        val state = AppState(restoring = false, connected = true, messages = originals,
            profile = CompanionProfile(name = "知语"))
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        compose.setContent {
            clipboard = LocalClipboardManager.current
            ZhiYuTheme(darkTheme = dark) {
                Scaffold(topBar = { CompanionTopBar("知语", null) { IconButton({}) { Icon(Icons.Outlined.MoreVert, "更多设置") } } }) {
                    Box(Modifier.fillMaxSize().padding(it)) { ChatScreen(state, viewModel) }
                }
            }
        }
        compose.onNodeWithTag("default_watercolor_background").assertExists()
        assertCompanionSegmentsAndCopy { clipboard?.getText()?.text }
        compose.onNodeWithTag("message_draft").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        compose.onNodeWithText("记忆").assertDoesNotExist()
        screenshot(if (dark) "watercolor-chat-dark.png" else "watercolor-chat-light.png")
    }

    @Test fun customBackgroundReplacesDefaultFloralArtwork() {
        val image = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(170, 194, 183)) }
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        // This tiny fixture is collected after composition disposal; never recycle while UI owns it.
        compose.setContent {
            clipboard = LocalClipboardManager.current
            ZhiYuTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppearanceImages provides AppearanceImages(background = image)) {
                    ChatScreen(AppState(restoring = false, connected = true, messages = originals), viewModel)
                }
            }
        }
        compose.onNodeWithTag("custom_chat_background").assertExists()
        compose.onNodeWithTag("default_watercolor_background").assertDoesNotExist()
        assertCompanionSegmentsAndCopy { clipboard?.getText()?.text }
    }

    private fun assertCompanionSegmentsAndCopy(copiedText: () -> String?) {
        val original = originals.last()
        val segments = ResponseSegments.split(original.content)
        assertEquals(original.content, segments.joinToString("") { it.text })
        segments.forEachIndexed { index, segment ->
            compose.onNodeWithTag("message_segment_${original.id}_$index").assertExists()
            compose.onNodeWithText(segment.text.trim()).assertIsDisplayed()
        }
        compose.onNode(hasContentDescription("复制消息") and hasAnyAncestor(hasTestTag("message_${original.id}"))).performClick()
        compose.runOnIdle { assertEquals(original.content, copiedText()) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            val sampled = buildSet {
                val stepX = maxOf(1, screenshot.width / 50)
                val stepY = maxOf(1, screenshot.height / 50)
                for (y in 0 until screenshot.height step stepY) {
                    for (x in 0 until screenshot.width step stepX) add(screenshot.getPixel(x, y))
                }
            }
            assertTrue("Screenshot must include rendered UI instead of a blank surface", sampled.size > 16)
            File(context.filesDir, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
    }
}
