package com.zhiyu.app.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.json.JSONObject
import java.io.File

/** Local operator rendering fixture: preserves credentials, binding and existing conversation. */
class QqStickerUiSmokeTest {
    @get:Rule(order = 0) val emulatorOnly = object : ExternalResource() {
        override fun before() {
            assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
            assumeTrue(InstrumentationRegistry.getArguments().getString("qqFixture") == "local-only")
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everydayShareCanRenderANewQqStickerWithoutRequestingOne() {
        val args = InstrumentationRegistry.getArguments()
        val ids = requireNotNull(args.getString("qqStickerIds")).split(",")
        val trigger = requireNotNull(args.getString("qqShare"))
        val reply = requireNotNull(args.getString("qqReply"))
        val prefix = requireNotNull(args.getString("qqWarmupPrefix"))
        require(ids.size == 18 && ids.all { it.matches(Regex("[a-f0-9]{32}")) })
        require(!trigger.contains("表情") && !trigger.contains("贴纸"))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("message_draft").fetchSemanticsNodes().isNotEmpty() }
        assumeTrue(compose.onAllNodesWithText("本地演示（不调用 AI）").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(trigger).assertDoesNotExist()
        val newMedia = SemanticsMatcher("new QQ preview attachment") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } in ids.map { id -> "media_$id" }
        }
        compose.onAllNodes(newMedia).assertCountEquals(0)
        warmUp(prefix)
        compose.onNodeWithTag("message_draft").performTextInput(trigger)
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(trigger).assertExists()
        compose.waitUntil(30_000) { compose.onAllNodes(newMedia).fetchSemanticsNodes().size == 1 }
        val id = compose.onAllNodes(newMedia).fetchSemanticsNodes().single()
            .config[SemanticsProperties.TestTag].removePrefix("media_")
        compose.onNodeWithTag("media_$id").performScrollTo().assertIsDisplayed()
        compose.waitUntil(30_000) {
            val bounds = compose.onNodeWithTag("media_$id").fetchSemanticsNode().boundsInWindow
            var decoded = false
            compose.activityRule.scenario.onActivity { decoded = hasDecodedSticker(it.window.decorView, bounds) }
            decoded
        }
        compose.onNodeWithText("图片加载失败，点击重试").assertDoesNotExist()
        compose.onAllNodes(hasText("ZHIYU_STICKER", substring = true)).assertCountEquals(0)
        captureProof(id, trigger)
    }

    private fun warmUp(prefix: String) {
        for (index in 1..4) {
            compose.onNodeWithTag("message_draft").performTextInput("$prefix$index")
            compose.onNodeWithContentDescription("发送消息").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("嗯，准备第 $index 轮，我在听。").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun captureProof(id: String, trigger: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val output = File(activity.getExternalFilesDir(null), "qq-sticker-ui.png")
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            File(activity.getExternalFilesDir(null), "qq-sticker-ui-view-proof.json").writeText(
                JSONObject().put("media_id", id).put("new_media_count", 1)
                    .put("fixture_only", true).put("image_decoded", true)
                    .put("ordinary_share", trigger).put("warmup_count", 4).toString(), Charsets.UTF_8)
        }
    }

    private fun hasDecodedSticker(view: View, bounds: Rect): Boolean {
        if (view is ImageView && view.contentDescription == "恋人发来的表情包" &&
            view.drawable != null && view.measuredWidth > 0 && view.measuredHeight > 0) {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            if (bounds.contains(Offset(location[0] + view.measuredWidth / 2f,
                    location[1] + view.measuredHeight / 2f))) return true
        }
        return view is ViewGroup && (0 until view.childCount).any { hasDecodedSticker(view.getChildAt(it), bounds) }
    }
}
