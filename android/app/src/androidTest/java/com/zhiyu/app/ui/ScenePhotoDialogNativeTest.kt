package com.zhiyu.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.data.remotescene.*
import com.zhiyu.app.ui.screens.ScenePhotoDialog
import com.zhiyu.app.ui.theme.ZhiYuTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Dialog clicks use a synthetic manager; no API, credentials or user files are accessed. */
class ScenePhotoDialogNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chat = "a".repeat(32)
    private val id = "b".repeat(32)
    private class Manager(private val value: ScenePhotoRow) : ScenePhotoManagement {
        var previews = 0; var reviews = 0; var retries = 0; var clears = 0
        override suspend fun page(chat: String, cursor: String?) = ScenePhotoPage(listOf(value), null)
        override suspend fun preview(chat: String, requestId: String): ByteArray { previews++; throw IllegalStateException("private-key-and-prompt") }
        override suspend fun recheck(chat: String, requestId: String) { reviews++ }
        override suspend fun retryFailed(chat: String, requestId: String) { retries++ }
        override suspend fun clear(chat: String, requestId: String) { clears++ }
    }
    private fun row(status: String = "failed", retry: Boolean = true) = ScenePhotoRow(id, "2026-10-14", "08:00", status, true, retry, true, true)
    private fun mount(manager: Manager): ScenePhotoController {
        lateinit var value: ScenePhotoController
        compose.setContent { ZhiYuTheme {
            val scope = rememberCoroutineScope()
            value = remember { ScenePhotoController(manager, scope) }
            DisposableEffect(value) { onDispose { value.dispose() } }
            ScenePhotoDialog(value) { }
        } }
        compose.runOnIdle { value.connect(chat) }
        compose.waitUntil(5000) { value.items.isNotEmpty() && !value.busy }
        return value
    }
    @Test fun retryExplainsChargingAndOnlyConfirmationSubmitsOneExplicitPaidAction() {
        val manager = Manager(row()); val value = mount(manager)
        compose.onNodeWithTag("scene_photo_retry_$id").performScrollTo().performClick()
        compose.onNodeWithText("重新生成这张照片？").assertExists()
        compose.onNodeWithText("这会重新调用生图服务并产生费用。仅适用于已明确失败的请求；结果未确认的请求不会重新生成。").assertExists()
        assertEquals(0, manager.retries)
        compose.onNodeWithTag("scene_photo_confirm_cancel").performClick(); assertEquals(0, manager.retries)
        compose.onNodeWithTag("scene_photo_retry_$id").performScrollTo().performClick()
        compose.onNodeWithTag("scene_photo_confirm").performClick()
        compose.waitUntil(5000) { manager.retries == 1 && !value.busy }
        assertEquals(0, manager.clears); assertEquals(0, manager.previews)
    }
    @Test fun unknownStatusNeverShowsPaidRetryAndTheListLoadsNoPreview() {
        val manager = Manager(row("unknown", true)); mount(manager)
        compose.onNodeWithText("结果未确认").assertExists()
        compose.onNodeWithTag("scene_photo_retry_$id").assertDoesNotExist()
        assertEquals(0, manager.previews); assertEquals(0, manager.retries)
        compose.onNodeWithText("private-key-and-prompt").assertDoesNotExist()
    }
    @Test fun clearRequiresConfirmationAndExplainsOriginalAndSentPicturesArePreserved() {
        val manager = Manager(row()); val value = mount(manager)
        compose.onNodeWithTag("scene_photo_clear_$id").performScrollTo().performClick()
        compose.onNodeWithText("清理候选图片？").assertExists()
        compose.onNodeWithText("只清理这张候选的本机像素。恋人原图和已发送的照片会保留；生成记录仍会保留，避免重复扣费。").assertExists()
        assertEquals(0, manager.clears)
        compose.onNodeWithTag("scene_photo_confirm_cancel").performClick(); assertEquals(0, manager.clears)
        compose.onNodeWithTag("scene_photo_clear_$id").performScrollTo().performClick()
        compose.onNodeWithTag("scene_photo_confirm").performClick()
        compose.waitUntil(5000) { manager.clears == 1 && !value.busy }
        assertEquals(0, manager.retries)
    }
    @Test fun previewAndReviewNeedExplicitClicksAndPrivateFailuresAreSanitized() {
        val manager = Manager(row("review_REJECTED", false)); val value = mount(manager)
        assertEquals(0, manager.previews); assertEquals(0, manager.reviews)
        compose.onNodeWithTag("scene_photo_preview_$id").performScrollTo().performClick()
        compose.waitUntil(5000) { manager.previews == 1 && !value.busy }
        compose.onNodeWithText("private-key-and-prompt").assertDoesNotExist()
        compose.onNodeWithTag("scene_photo_review_$id").performScrollTo().performClick()
        compose.waitUntil(5000) { manager.reviews == 1 && !value.busy }
        assertEquals(0, manager.retries); assertEquals(0, manager.clears)
    }
}
