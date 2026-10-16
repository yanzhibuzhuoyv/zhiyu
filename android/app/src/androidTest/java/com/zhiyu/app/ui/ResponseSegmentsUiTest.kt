package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.ChatDisplayRules
import com.zhiyu.app.ui.screens.ChatMessageBubble
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class ResponseSegmentsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun foreground() { compose.waitUntil(10_000) { compose.activity.hasWindowFocus() } }

    @Test fun companionHasThreeIndependentOutlinedBubblesAndSecondPrecisionTimePerPart() {
        val message = Message("segments", "assistant", "好啊。等我一下！你先挑地方？", emptyList(), "2026-10-16T12:34:56+08:00")
        compose.setContent { ZhiYuTheme { ChatMessageBubble(message) } }
        (0..2).forEach { index ->
            compose.onNodeWithTag("message_segment_segments_$index").assertExists()
            val timeTag = if (index == 2) "message_time_segments" else "message_segment_time_segments_$index"
            compose.onNodeWithTag(timeTag).assertTextEquals(ChatDisplayRules.messageTime(message.createdAt)!!)
        }
        compose.onNodeWithTag("message_segments").assertExists()
    }

    @Test fun copyStillReturnsTheCompleteAssistantOriginalIncludingLineBreaks() {
        val text = "嗯。\n\n  我听着呢🍎！"
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        compose.setContent {
            clipboard = LocalClipboardManager.current
            ZhiYuTheme { ChatMessageBubble(Message("copysegments", "assistant", text, emptyList(), "now")) }
        }
        compose.onNodeWithTag("message_segment_copysegments_1").assertExists()
        compose.onNodeWithContentDescription("复制消息").performClick()
        compose.runOnIdle { assertEquals(text, clipboard?.getText()?.text) }
    }
}
