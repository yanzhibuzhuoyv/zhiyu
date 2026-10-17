package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.domain.Message
import com.zhiyu.app.ui.screens.ChatMessageBubble
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Rule
import org.junit.Test

class VoicePresentationNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun spokenContentIsNotAlsoAutomaticallyRenderedAsTheSameText() {
        val original = "这段只通过语音发给你，别着急。"
        compose.setContent { ZhiYuTheme { ChatMessageBubble(Message("spoken", "assistant", original,
            emptyList(), "2026-10-16T12:00:00Z", origin = "voice_reply:${"a".repeat(32)}"),
            voiceContent = { Text("合成语音条") }) } }
        compose.onNodeWithText("合成语音条").assertExists()
        compose.onNodeWithText(original).assertDoesNotExist()
    }
    @Test fun ordinaryMessagesKeepTheirTextWithoutACopyButton() {
        compose.setContent { ZhiYuTheme { ChatMessageBubble(Message("ordinary", "assistant", "我听着呢。",
            emptyList(), "2026-10-16T12:00:00Z")) } }
        compose.onNodeWithText("我听着呢。").assertExists()
        compose.onNodeWithContentDescription("复制消息").assertDoesNotExist()
    }
}
