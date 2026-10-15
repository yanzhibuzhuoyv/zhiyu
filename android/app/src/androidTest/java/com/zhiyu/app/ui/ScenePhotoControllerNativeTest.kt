package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zhiyu.app.data.remotescene.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic manager and pixels only. Does not access private candidates, credentials or a network. */
class ScenePhotoControllerNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chatA = "a".repeat(32)
    private val chatB = "b".repeat(32)
    private val idA = "c".repeat(32)
    private val idB = "d".repeat(32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: ScenePhotoController? = null
    @After fun cleanup() { compose.runOnIdle { controller?.dispose() }; scope.cancel() }

    private fun row(id: String = idA, status: String = "review_REJECTED", retry: Boolean = false) =
        ScenePhotoRow(id, "2026-10-14", "08:00", status, true, retry, true, true)
    private class Manager : ScenePhotoManagement {
        var pageBlock: suspend (String, String?) -> ScenePhotoPage = { _, _ -> ScenePhotoPage(emptyList(), null) }
        var previewBlock: suspend (String, String) -> ByteArray = { _, _ -> byteArrayOf(1) }
        var actionBlock: suspend () -> Unit = { }
        val pages = CopyOnWriteArrayList<Pair<String, String?>>()
        val previews = CopyOnWriteArrayList<Pair<String, String>>()
        val actions = CopyOnWriteArrayList<Triple<String, String, String>>()
        override suspend fun page(chat: String, cursor: String?): ScenePhotoPage { pages.add(chat to cursor); return pageBlock(chat, cursor) }
        override suspend fun preview(chat: String, requestId: String): ByteArray { previews.add(chat to requestId); return previewBlock(chat, requestId) }
        override suspend fun recheck(chat: String, requestId: String) { actions.add(Triple("review", chat, requestId)); actionBlock() }
        override suspend fun retryFailed(chat: String, requestId: String) { actions.add(Triple("retry", chat, requestId)); actionBlock() }
        override suspend fun clear(chat: String, requestId: String) { actions.add(Triple("clear", chat, requestId)); actionBlock() }
    }
    private fun start(manager: Manager, decoder: ScenePhotoPreviewDecoder = ScenePhotoPreviewDecoder { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }): ScenePhotoController {
        val value = ScenePhotoController(manager, scope, decoder); controller = value
        compose.runOnIdle { value.connect(chatA) }
        compose.waitUntil(5000) { !value.busy }
        return value
    }
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.eraseColor(0xff456789.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun paginationReplacesOneBoundedPageAndNeverLoadsPixelsAutomatically() {
        val manager = Manager().apply { pageBlock = { _, cursor -> ScenePhotoPage(listOf(row(if (cursor == null) idA else idB)), if (cursor == null) "next-page" else null) } }
        val value = start(manager)
        assertEquals(listOf(idA), value.items.map { it.requestId }); assertTrue(manager.previews.isEmpty())
        compose.runOnIdle { value.nextPage(); assertTrue(value.busy); value.nextPage() }
        compose.waitUntil(5000) { !value.busy }
        assertEquals(listOf(idB), value.items.map { it.requestId }); assertEquals(2, value.pageNumber); assertTrue(value.canGoBack)
        assertEquals(listOf(chatA to null, chatA to "next-page"), manager.pages)
        compose.runOnIdle { value.previousPage() }; compose.waitUntil(5000) { !value.busy }
        assertEquals(listOf(idA), value.items.map { it.requestId }); assertEquals(1, value.pageNumber)
        assertFalse(value.canGoBack); assertTrue(manager.previews.isEmpty())
    }
    @Test fun oversizedPageFailsClosedWithoutDisplayingPrivateExceptionText() {
        val manager = Manager().apply { pageBlock = { _, _ -> ScenePhotoPage((0..30).map { row(it.toString().padStart(32, '0')) }, null) } }
        val value = start(manager)
        assertTrue(value.items.isEmpty()); assertNotNull(value.error)
        manager.pageBlock = { _, _ -> throw IllegalStateException("private-key-and-prompt") }
        compose.runOnIdle { value.reload() }; compose.waitUntil(5000) { !value.busy }
        assertFalse(value.error.orEmpty().contains("private-key-and-prompt"))
    }
    @Test fun supersededPageCannotReplaceNewChatOrResetItsBusyFlag() {
        val gateA = CompletableDeferred<Unit>(); val gateB = CompletableDeferred<Unit>()
        val manager = Manager().apply { pageBlock = { chat, _ -> withContext(NonCancellable) {
            if (chat == chatA) gateA.await() else gateB.await()
            ScenePhotoPage(listOf(row(if (chat == chatA) idA else idB)), null)
        } } }
        val value = ScenePhotoController(manager, scope); controller = value
        compose.runOnIdle { value.connect(chatA); assertTrue(value.busy) }
        compose.waitUntil(5000) { manager.pages.any { it.first == chatA } }
        compose.runOnIdle { value.connect(chatB); assertTrue(value.busy); assertTrue(value.items.isEmpty()) }
        compose.waitUntil(5000) { manager.pages.any { it.first == chatB } }
        gateA.complete(Unit)
        compose.runOnIdle { assertTrue(value.busy); assertTrue(value.items.isEmpty()) }
        gateB.complete(Unit); compose.waitUntil(5000) { !value.busy }
        assertEquals(listOf(idB), value.items.map { it.requestId }); assertNull(value.error)
    }
    @Test fun realPreviewIsSampledAndReplacingOrDisconnectingRecyclesTheOwnedBitmap() {
        val bytes = png(1536, 768)
        val manager = Manager().apply { pageBlock = { _, _ -> ScenePhotoPage(listOf(row(), row(idB)), null) }; previewBlock = { _, _ -> bytes } }
        val value = start(manager, AndroidScenePhotoPreviewDecoder)
        compose.runOnIdle { value.showPreview(idA) }; compose.waitUntil(5000) { !value.busy && value.preview != null }
        val first = requireNotNull(value.preview)
        assertTrue(maxOf(first.width, first.height) <= 512); assertTrue(first.width >= first.height)
        compose.runOnIdle { value.showPreview(idB) }; compose.waitUntil(5000) { !value.busy && value.selectedId == idB && value.preview != null }
        val second = requireNotNull(value.preview)
        assertTrue(first.isRecycled); assertFalse(second.isRecycled); assertEquals(2, manager.previews.size)
        compose.runOnIdle { value.connect(null) }
        assertTrue(second.isRecycled); assertNull(value.preview); assertNull(value.selectedId); assertTrue(value.items.isEmpty())
    }
    @Test fun cancelledDecoderNeverLeaksAnUnadoptedNativeBitmap() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var decoded: Bitmap? = null
        val manager = Manager().apply { pageBlock = { _, _ -> ScenePhotoPage(listOf(row()), null) }; previewBlock = { _, _ -> png(16, 16) } }
        val value = start(manager, ScenePhotoPreviewDecoder {
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { decoded = it; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        })
        try {
            compose.runOnIdle { value.showPreview(idA) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { value.connect(null) }
        } finally { release.countDown() }
        compose.waitUntil(5000) { decoded?.isRecycled == true }
        assertNull(value.preview); assertNull(value.selectedId)
    }
    @Test fun retryNeedsFailedStatusConfirmationAndCannotReuseAnOldChatConfirmation() {
        val manager = Manager().apply { pageBlock = { chat, _ -> ScenePhotoPage(listOf(row(if (chat == chatA) idA else idB, "failed", true)), null) } }
        val value = start(manager)
        compose.runOnIdle { value.requestRetry(idA) }
        val token = requireNotNull(value.confirmation)
        assertTrue(manager.actions.isEmpty())
        compose.runOnIdle { value.cancelConfirmation() }; assertTrue(manager.actions.isEmpty())
        compose.runOnIdle { value.requestRetry(idA); value.connect(chatB) }
        compose.waitUntil(5000) { !value.busy }
        compose.runOnIdle { value.confirm(token) }
        assertTrue(manager.actions.isEmpty()); assertNull(value.confirmation)
        compose.runOnIdle { value.requestRetry(idB); value.confirm(requireNotNull(value.confirmation)); assertTrue(value.busy) }
        compose.waitUntil(5000) { !value.busy }
        assertEquals(listOf(Triple("retry", chatB, idB)), manager.actions)
    }
    @Test fun unknownRecordCannotRetryEvenIfAFakeManagerSetsRetryFlag() {
        val manager = Manager().apply { pageBlock = { _, _ -> ScenePhotoPage(listOf(row(status = "unknown", retry = true)), null) } }
        val value = start(manager)
        compose.runOnIdle { value.requestRetry(idA) }
        assertNull(value.confirmation); assertTrue(manager.actions.isEmpty())
    }
    @Test fun confirmedClearAndExplicitReviewUseOnlyTheSelectedOwnerAndBlockDoubleActions() {
        val gate = CompletableDeferred<Unit>()
        val manager = Manager().apply { pageBlock = { _, _ -> ScenePhotoPage(listOf(row()), null) }; actionBlock = { gate.await() } }
        val value = start(manager)
        compose.runOnIdle { value.requestClear(idA) }
        assertTrue(manager.actions.isEmpty())
        compose.runOnIdle { value.cancelConfirmation(); value.recheck(idA); assertTrue(value.busy); value.recheck(idA); value.requestClear(idA) }
        compose.waitUntil(5000) { manager.actions.size == 1 }
        assertEquals(Triple("review", chatA, idA), manager.actions.single()); assertNull(value.confirmation)
        gate.complete(Unit); compose.waitUntil(5000) { !value.busy }
        compose.runOnIdle { value.requestClear(idA); value.confirm(requireNotNull(value.confirmation)) }
        compose.waitUntil(5000) { !value.busy }
        assertEquals(listOf(Triple("review", chatA, idA), Triple("clear", chatA, idA)), manager.actions)
    }
    @Test fun allPublicStatusesHaveChineseLabelsAndDoNotEchoUnknownTechnicalValues() {
        val states = listOf("reserved", "running", "unknown", "failed", "success", "stale", "review_APPROVED", "review_REJECTED", "review_UNKNOWN", "review_UNAVAILABLE", "removed", "invalid")
        states.forEach { state -> val text = scenePhotoStatus(state); assertTrue(text.any { it.code in 0x4e00..0x9fff }); assertFalse(text.contains(state)) }
        assertEquals("状态待确认", scenePhotoStatus("private-provider-error-body"))
    }
}
