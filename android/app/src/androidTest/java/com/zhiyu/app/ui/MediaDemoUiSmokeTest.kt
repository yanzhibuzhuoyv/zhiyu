package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File

/** Operator smoke: requires a preconfigured emulator demo; never opens a real Key or resets data. */
class MediaDemoUiSmokeTest {
    @get:Rule(order = 0) val emulatorOnly = object : ExternalResource() {
        override fun before() {
            assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cachedStickerRendersInTheNativeChat() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("message_draft").fetchSemanticsNodes().isNotEmpty() }
        assumeTrue(compose.onAllNodesWithText("本地演示（不调用 AI）").fetchSemanticsNodes().isNotEmpty())
        val trigger = "想你，发个表情包抱抱"
        // Replaying this operator smoke also verifies the persisted attachment after a restart.
        if (compose.onAllNodesWithText(trigger).fetchSemanticsNodes().isEmpty()) send(trigger)
        waitForMedia(1)
        compose.onNodeWithContentDescription("更多设置").performClick()
        compose.onNodeWithText("记忆").assertDoesNotExist()
        compose.onNodeWithText("头像与背景").assertExists()
        compose.onNodeWithText("恋人外观").assertExists()
        compose.onNodeWithText("主动联系").assertDoesNotExist()
        compose.onNodeWithText("天气与日历").assertDoesNotExist()
        compose.onNodeWithText("聊天字号").assertExists()
        compose.onNodeWithText("日历").assertExists()
        compose.activityRule.scenario.onActivity { activity ->
            val output = File(activity.getExternalFilesDir(null), "media-demo-ui.png")
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }

    private fun send(text: String) {
        compose.onNodeWithTag("message_draft").performTextInput(text)
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("companion_typing").fetchSemanticsNodes().isEmpty() }
    }

    private fun waitForMedia(count: Int) {
        val media = SemanticsMatcher("media attachment") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("media_")
        }
        compose.waitUntil(15_000) { compose.onAllNodes(media).fetchSemanticsNodes().size >= count }
        compose.waitUntil(15_000) {
            var decoded = false
            compose.activityRule.scenario.onActivity { decoded = hasDecodedSticker(it.window.decorView) }
            decoded
        }
        compose.onNodeWithText("图片加载失败，点击重试").assertDoesNotExist()
    }

    private fun hasDecodedSticker(view: View): Boolean {
        if (view is ImageView && view.contentDescription == "恋人发来的表情包" &&
            view.drawable != null && view.measuredWidth > 0 && view.measuredHeight > 0) return true
        return view is ViewGroup && (0 until view.childCount).any { hasDecodedSticker(view.getChildAt(it)) }
    }
}
