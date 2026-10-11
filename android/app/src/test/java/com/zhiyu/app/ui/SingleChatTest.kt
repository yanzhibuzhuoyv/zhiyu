package com.zhiyu.app.ui

import android.app.Application
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SingleChatTest {
    @Test fun brokenPhotoDraftDoesNotUndoAHealthyRestoredConnection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CompanionRepository().apply { brokenDraft = true }
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            assertTrue("图片草稿错误不能被当成登录失败", model.state.value.connected)
            assertEquals("original", model.state.value.selectedId)
            assertEquals(repository.binding, model.state.value.binding)
            assertNotNull(model.state.value.error)
            assertEquals("test-key", repository.connection!!.apiKey)
        } finally { Dispatchers.resetMain() }
    }
    private fun runModel(empty: Boolean = false, setup: (CompanionRepository) -> Unit = {},
        block: suspend TestScope.(ChatViewModel, CompanionRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CompanionRepository()
            if (empty) repository.chats = emptyList()
            setup(repository)
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            block(model, repository)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun restoresOneStableChatAndRequestsOnlyALimitedHistoryPage() = runModel { model, repository ->
        assertEquals("original", model.state.value.selectedId)
        assertEquals(listOf("original" to null), repository.historyCalls)
        repository.chats = repository.chats.reversed()
        model.refresh()
        advanceUntilIdle()
        assertEquals("original", model.state.value.selectedId)
        assertEquals(0, repository.created)
    }

    @Test fun createsTheFirstChatAutomaticallyAndReusesItAfterRefresh() = runModel(empty = true) { model, repository ->
        assertEquals("created", model.state.value.selectedId)
        assertEquals(1, repository.created)
        model.refresh()
        advanceUntilIdle()
        assertEquals("created", model.state.value.selectedId)
        assertEquals(1, repository.created)
    }

    @Test fun olderHistoryUsesCursorAndKeepsOnlyOneBatchInMemory() = runModel { model, repository ->
        model.loadOlder()
        advanceUntilIdle()
        assertEquals("cursor", repository.historyCalls.last().second)
        assertEquals(100, model.state.value.messages.size)
        assertTrue(model.state.value.viewingOlder)
        assertEquals("old-0", model.state.value.messages.first().id)
        model.refresh()
        advanceUntilIdle()
        assertFalse(model.state.value.viewingOlder)
        assertEquals("recent-0", model.state.value.messages.first().id)
    }

    @Test fun sendingFromOldHistoryReturnsToTheRecentBatchInTheSameChat() = runModel { model, repository ->
        model.loadOlder()
        advanceUntilIdle()
        model.draft("记得我们的第一次见面吗？")
        model.send()
        advanceUntilIdle()
        assertEquals(listOf("original"), repository.sentTo)
        assertEquals("original", model.state.value.selectedId)
        assertFalse(model.state.value.viewingOlder)
        assertTrue(model.state.value.messages.size <= 100)
        assertEquals(0, repository.created)
    }

    @Test fun changingServerAndLoggingOutPreservesTheSelectedCompanyAndModel() = runModel { model, repository ->
        model.disconnect()
        advanceUntilIdle()
        model.serverUrl("http://10.0.2.2:8900")
        advanceUntilIdle()
        model.provider("qwen")
        model.model("different")
        assertEquals("deepseek", model.state.value.providerId)
        assertEquals("bound-model", model.state.value.model)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals("", model.state.value.apiKey)
    }

    private fun photo() = ChatImageDraft(MediaAttachment("a".repeat(32), "image", MediaRules.path("a".repeat(32)),
        "image/png", "b".repeat(64), null, 0, "用户图片"))

    @Test fun voiceSendPreservesTheKeyboardDraftAndUnsentPhoto() = runModel { model, repository ->
        val picture = photo()
        model.draft("键盘里还没说完的话"); model.draftImage(picture)
        model.sendVoiceText("这句话来自语音")
        advanceUntilIdle()
        assertEquals("键盘里还没说完的话", model.state.value.draft)
        assertEquals(picture, model.state.value.draftImage)
        assertEquals("这句话来自语音", repository.sentContents.single())
        assertTrue(repository.sentPhotos.isEmpty())
        assertTrue(repository.discardedDrafts.isEmpty())
    }

    @Test fun photoFailureRetainsTheDraftAndSuccessClearsOnlyThatPhoto() = runModel { model, repository ->
        val picture = photo(); model.draftImage(picture); repository.rejectPhoto = true
        model.send(); advanceUntilIdle()
        assertEquals(picture, model.state.value.draftImage)
        assertNotNull(model.state.value.error)
        assertTrue(repository.discardedDrafts.isEmpty())
        repository.rejectPhoto = false
        model.send(); advanceUntilIdle()
        assertNull(model.state.value.draftImage)
        assertEquals(listOf(picture.attachment.id), repository.discardedDrafts)
        assertEquals("", repository.sentContents.single())
    }

    @Test fun committedPhotoReplyRemainsVisibleWhenDraftCleanupFailsAndCannotBeResent() = runModel { model, repository ->
        val picture = photo()
        model.draft("看看这张照片"); model.draftImage(picture)
        repository.rejectDraftCleanup = true
        model.send(); advanceUntilIdle()

        assertEquals(listOf(picture.attachment), repository.sentPhotos)
        assertEquals(listOf(picture.attachment), model.state.value.messages.first { it.id == "new-user" }.mediaAttachments)
        assertEquals("记得", model.state.value.messages.last().content)
        assertNull("清理草稿失败不能把成功发出的图片留在可重发输入框", model.state.value.draftImage)
        assertEquals("", model.state.value.draft)
        assertNull("不能把发送后清理故障伪装成发送失败", model.state.value.error)
        assertTrue(model.state.value.notice.orEmpty().contains("已发送"))
        assertTrue(model.state.value.connected)
        assertEquals(repository.binding, model.state.value.binding)

        model.send(); advanceUntilIdle()
        assertEquals("原图已经发出，空输入框不能再次调用图片 API", 1, repository.sentPhotos.size)
    }

    @Test fun restoredDraftAlreadyPresentInUserMessagesIsNotResendableEvenIfCleanupFails() = runModel(setup = {
        it.restoredDraft = photo(); it.recordedPhoto = photo().attachment; it.rejectDraftCleanup = true
    }) { model, repository ->
        assertTrue(model.state.value.connected)
        assertNull(model.state.value.draftImage)
        assertNull("已发出草稿的清理失败不撤销登录，也不显示为发送失败", model.state.value.error)
        assertTrue(model.state.value.notice.orEmpty().contains("已发送"))
        assertTrue(repository.sentPhotos.isEmpty())
    }

    @Test fun separatelySelectedSameBytesKeepTheirOwnUnsentAttachmentId() = runModel(setup = {
        val old = photo().attachment
        it.recordedPhoto = old
        it.restoredDraft = ChatImageDraft(old.copy(id = "c".repeat(32), url = MediaRules.path("c".repeat(32))))
    }) { model, repository ->
        assertEquals(repository.restoredDraft, model.state.value.draftImage)
        assertEquals("b".repeat(64), model.state.value.draftImage!!.attachment.sha256)
        assertTrue("相同 SHA 不能清掉不同 ID 的新选择", repository.discardedDrafts.isEmpty())
        assertTrue(model.state.value.connected)
    }
}

private class CompanionRepository : ChatRepository {
    val binding = CompanionBinding("deepseek", "bound-model")
    var connection: SavedConnection? = SavedConnection("https://host", binding.providerId, "test-key", binding.model)
    var chats = listOf(chat("newer", "2026-10-02"), chat("other", "2026-09-01", "another-model"), chat("original", "2026-10-01"))
    val historyCalls = mutableListOf<Pair<String, String?>>()
    val sentTo = mutableListOf<String>()
    val sentContents = mutableListOf<String>()
    val sentPhotos = mutableListOf<MediaAttachment>()
    val discardedDrafts = mutableListOf<String>()
    var rejectPhoto = false
    var rejectDraftCleanup = false
    var brokenDraft = false
    var restoredDraft: ChatImageDraft? = null
    var recordedPhoto: MediaAttachment? = null
    var created = 0
    private fun chat(id: String, date: String, model: String = binding.model) = Conversation(id, "与你", model, true, date, date)
    override suspend fun savedBinding() = binding
    override suspend fun savedPhotoDraft(): ChatImageDraft? {
        if (brokenDraft) throw IllegalArgumentException("图片草稿损坏，请重新选择")
        return restoredDraft
    }
    override suspend fun savedConnection() = connection
    override suspend fun providers(serverUrl: String) = listOf(Provider("qwen", "通义", "other", emptyList(), "", ""), Provider("deepseek", "DeepSeek", "default", emptyList(), "", ""))
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) { connection = SavedConnection(serverUrl, providerId, apiKey, model) }
    override suspend fun logout() { connection = null }
    override suspend fun forgetLocalConnection() { connection = null }
    override suspend fun conversations() = chats
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation { created++; val result = chat("created", "2026-10-03", model).copy(profile = profile); chats = listOf(result); return result }
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation {
        val result = chats.first { it.id == id }.copy(profile = profile)
        chats = chats.map { if (it.id == id) result else it }
        return result
    }
    override suspend fun changeRag(id: String, enabled: Boolean) = chats.first { it.id == id }.copy(useRag = enabled)
    override suspend fun deleteConversation(id: String): Unit = error("No conversation deletion in companion UI")
    override suspend fun messages(id: String): List<Message> = error("Must not fetch entire long history")
    override suspend fun history(id: String, before: String?): MessagePage {
        historyCalls.add(id to before)
        val prefix = if (before == null) "recent" else "old"
        return MessagePage(List(100) { Message("$prefix-$it", if (it % 2 == 0) "user" else "assistant", "test-$it", emptyList(), "2026-10-01",
            if (it == 0 && before == null) listOfNotNull(recordedPhoto) else emptyList()) }, before == null, if (before == null) "cursor" else null)
    }
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> {
        sentTo.add(id)
        sentContents.add(content)
        return Message("new-user", "user", content, emptyList(), "now") to Message("new-ai", "assistant", "记得", emptyList(), "now")
    }
    override suspend fun sendPhotoMessage(id: String, content: String, attachment: MediaAttachment): Pair<Message, Message> {
        if (rejectPhoto) throw ApiFailure(0, "合成测试连接失败")
        sentPhotos.add(attachment)
        val pair = sendMessage(id, content)
        return pair.first.copy(mediaAttachments = listOf(attachment)) to pair.second
    }
    override suspend fun discardPhotoDraft(id: String) {
        if (rejectDraftCleanup) throw IllegalStateException("合成测试草稿 commit 失败")
        discardedDrafts.add(id)
    }
    override suspend fun documents() = emptyList<Document>()
    override suspend fun memory(id: String, before: String?): MemoryPage = error("Unused")
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry = error("Unused")
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry = error("Unused")
    override suspend fun upload(file: UploadedFile): Document = error("Unused")
    override suspend fun deleteDocument(id: String): Unit = error("Unused")
}
