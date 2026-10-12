package com.zhiyu.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.screens.ChatScreen
import com.zhiyu.app.ui.theme.ZhiYuTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Actual send-button and rendered picture; only synthetic repository replies, no API or phone data. */
class PhotoSendUiNativeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = NativeV4Context(base)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val models = ViewModelStore()
    private val gate = CompletableDeferred<Unit>()
    private val history = mutableListOf<Message>()
    private lateinit var viewModel: ChatViewModel
    private lateinit var draft: ChatImageDraft
    private var failReply = false
    private val chat = Conversation("a".repeat(32), "合成聊天", "qwen-vl-max", true, "2026-10-11T00:00:00Z", "2026-10-11T00:00:00Z")
    private val binding = CompanionBinding("qwen", chat.model)

    @Before fun setup() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val bytes = try { bitmap.eraseColor(0xff714e8a.toInt()); ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            finally { bitmap.recycle() }
        draft = ChatPhotoStore(context).importBytes(bytes)
        val repository = Proxy.newProxyInstance(ChatRepository::class.java.classLoader, arrayOf(ChatRepository::class.java)) { _, method, args ->
            when (method.name) {
                "savedConnection" -> SavedConnection(LocalChatRepository.LOCAL_URL, "qwen", "synthetic-key", chat.model)
                "savedBinding" -> binding
                "connectionNotice", "savedPhotoDraft" -> null
                "providers" -> DirectProviderCatalog.providers()
                "conversations" -> listOf(chat)
                "documents" -> emptyList<Document>()
                "history" -> MessagePage(history.toList(), false, null)
                "discardPhotoDraft" -> Unit
                "sendPhotoMessage" -> {
                    @Suppress("UNCHECKED_CAST") val continuation = args!!.last() as Continuation<Any?>
                    @Suppress("UNCHECKED_CAST") val submitted = if (args.size == 5) args[3] as (Message) -> Unit else null
                    scope.launch {
                        val result = runCatching {
                            val user = Message("photo-user", "user", "", emptyList(), "2026-10-11T00:00:01Z", listOf(draft.attachment))
                            history.add(user); submitted?.invoke(user)
                            gate.await()
                            if (failReply) throw ApiFailure(503, "合成回复暂时失败", "provider_unavailable")
                            val answer = Message("photo-reply", "assistant", "看到啦。", emptyList(), "2026-10-11T00:00:02Z")
                            history.add(answer); user to answer
                        }
                        continuation.resumeWith(result)
                    }
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected synthetic call ${method.name}")
            }
        } as ChatRepository
        val media = MediaController(context, scope)
        runBlocking { media.connect(chat.id) }
        compose.runOnUiThread { viewModel = ChatViewModel(repository, DocumentReader(context)); models.put("photo-send", viewModel) }
        compose.waitUntil(10_000) { !viewModel.state.value.restoring }
        compose.runOnUiThread { viewModel.draftImage(draft) }
        compose.setContent {
            val state by viewModel.state.collectAsState()
            ZhiYuTheme { CompositionLocalProvider(LocalMediaController provides media) { ChatScreen(state, viewModel) } }
        }
    }
    @After fun cleanup() {
        gate.complete(Unit)
        compose.runOnUiThread { models.clear(); scope.cancel() }
        context.close()
        val root = context.filesDir.canonicalFile
        check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
        check(root.deleteRecursively())
    }
    @Test fun tappingSendShowsCommittedPhotoAndClearsDraftBeforeTheAnswer() {
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.waitUntil(10_000) { viewModel.state.value.messages.any { it.id == "photo-user" } }
        assertTrue(viewModel.state.value.isBusy)
        assertNull(viewModel.state.value.draftImage)
        assertNull(viewModel.state.value.pendingImage)
        assertRenderedPhoto()
        compose.onNodeWithText("对方正在输入中").assertIsDisplayed()
        gate.complete(Unit)
        compose.waitUntil(10_000) { !viewModel.state.value.isBusy }
        assertEquals(1, viewModel.state.value.messages.count { it.role == "user" })
        assertEquals(1, viewModel.state.value.messages.count { it.role == "assistant" })
    }
    @Test fun failedAnswerKeepsTheSubmittedPhotoVisibleAndDoesNotRestoreItsDraft() {
        failReply = true
        compose.onNodeWithContentDescription("发送消息").performClick()
        gate.complete(Unit)
        compose.waitUntil(10_000) { viewModel.state.value.error != null && !viewModel.state.value.isBusy }
        assertEquals(history.single(), viewModel.state.value.messages.single())
        assertNull(viewModel.state.value.draftImage)
        assertRenderedPhoto()
        assertTrue(viewModel.state.value.error!!.contains("合成回复暂时失败"))
    }

    @Test fun completedPickerImportDuringBusyStateIsImmediatelyUsableWithoutReopening() {
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.waitUntil(10_000) { viewModel.state.value.messages.any { it.id == "photo-user" } }
        assertTrue(viewModel.state.value.isBusy)
        val bytes = LocalMediaStore(context).download(draft.attachment.id, draft.attachment.sha256)
        val store = ChatPhotoStore(context)
        val returnedFromPicker = store.importBytes(bytes).also(store::saveDraft)
        assertNotEquals(draft.attachment.id, returnedFromPicker.attachment.id)
        // This is the real controller callback after its IO work. It can overlap restore/refresh/send.
        compose.runOnUiThread { viewModel.draftImage(returnedFromPicker) }
        assertEquals("A completed import must not be silently ignored while busy",
            returnedFromPicker, viewModel.state.value.draftImage)
        gate.complete(Unit)
        compose.waitUntil(10_000) { !viewModel.state.value.isBusy }
        assertEquals("Completing the previous send must not clear a later attachment",
            returnedFromPicker, viewModel.state.value.draftImage)
        assertEquals(returnedFromPicker, store.readDraft())
    }

    private fun assertRenderedPhoto() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("图片准备中…").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("图片加载失败，点击重试").assertDoesNotExist()
        val pixels = compose.onNodeWithTag("media_${draft.attachment.id}")
            .assertIsDisplayed().captureToImage().toPixelMap()
        assertEquals(0xff714e8a.toInt(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }
}
