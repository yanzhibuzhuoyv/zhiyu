package com.zhiyu.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import com.zhiyu.app.domain.AppearanceSelection
import com.zhiyu.app.domain.AppearanceTarget
import com.zhiyu.app.domain.Message
import com.zhiyu.app.ui.screens.AppearanceDialog
import com.zhiyu.app.ui.screens.ChatMessageBubble
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Fixed messages and local UI only; never authenticates, clears credentials, or calls a provider. */
@Suppress("DEPRECATION")
class AppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun foregroundWindowReady() {
        compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
    }

    @Test fun outlinedBubbleShowsTheBackgroundAndHasNoMemoryAction() {
        val background = Color(0xFF64B5F6)
        val message = Message("outline", "assistant", "我在这里。", emptyList(), "now")
        compose.setContent { ZhiYuTheme { Box(Modifier.background(background).padding(16.dp)) { ChatMessageBubble(message, "知心") } } }
        compose.onNodeWithTag("message_outline").assertExists().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("管理消息记忆").assertDoesNotExist()
        compose.onNodeWithText("记忆").assertDoesNotExist()
        compose.onNodeWithContentDescription("恋人头像").assertExists()
        val pixels = compose.onNodeWithTag("message_outline").captureToImage().toPixelMap()
        assertEquals(background.toArgb(), pixels[pixels.width / 2, 5].toArgb())
    }

    @Test fun copyingKeepsTheCompleteOriginalMessage() {
        val original = "第一个晚上🍎\n  我原本想这样说。\n最后一行"
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        compose.setContent {
            clipboard = LocalClipboardManager.current
            ZhiYuTheme { ChatMessageBubble(Message("copy", "user", original, emptyList(), "now")) }
        }
        compose.onNodeWithContentDescription("复制消息").performClick()
        compose.runOnIdle { assertEquals(original, clipboard?.getText()?.text) }
        compose.onNodeWithContentDescription("我的头像").assertExists()
    }

    @Test fun resettingBackgroundKeepsBothAvatarSelections() {
        var result: AppearanceSelection? = null
        compose.setContent {
            var selection by remember { mutableStateOf(AppearanceSelection("content://photos/user", "content://photos/oc", "content://photos/background")) }
            ZhiYuTheme {
                AppearanceDialog(selection, AppearanceImages(), false, {}, { target ->
                    selection = selection.with(target, null)
                    result = selection
                }, {})
            }
        }
        compose.onNodeWithTag("reset_background").performScrollTo().performClick()
        compose.onNodeWithTag("reset_background").assertDoesNotExist()
        compose.onNodeWithTag("reset_user_avatar").assertExists()
        compose.onNodeWithTag("reset_companion_avatar").assertExists()
        compose.runOnIdle {
            assertNull(result?.background)
            assertEquals("content://photos/user", result?.userAvatar)
            assertEquals("content://photos/oc", result?.companionAvatar)
        }
    }

    @Test fun imageChoiceTargetsTheCorrectAvatarAndDisablesWhileLoading() {
        var chosen: AppearanceTarget? = null
        var loading by mutableStateOf(false)
        compose.setContent {
            ZhiYuTheme { AppearanceDialog(AppearanceSelection(), AppearanceImages(), loading, { chosen = it }, {}, {}) }
        }
        compose.onNodeWithTag("choose_companion_avatar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppearanceTarget.COMPANION_AVATAR, chosen); loading = true }
        compose.onNodeWithTag("choose_companion_avatar").assertIsNotEnabled()
    }
}
