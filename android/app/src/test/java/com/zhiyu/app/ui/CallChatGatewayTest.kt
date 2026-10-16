package com.zhiyu.app.ui

import android.app.Application
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallChatGatewayTest {
    @Test fun callCompletionNeverPublishesIntoTheTextTimeline() = model { model, repo ->
        model.draft("等会再发的文字")
        val snapshot = model.state.value
        assertEquals("陪你聊聊。", model.sendCallText(repo.chat.id, "刚刚醒了"))
        assertEquals(2, repo.rows.size)
        assertEquals(snapshot.messages, model.state.value.messages)
        assertEquals(snapshot.draft, model.state.value.draft)
        assertEquals(snapshot.busy, model.state.value.busy)
        assertNull(model.state.value.pendingMessage)
    }

    @Test fun pendingCallDoesNotShowAKeyboardMessageOrTakeTheTextOperation() = model { model, repo ->
        repo.answer = { awaitCancellation() }
        val call = launch { model.sendCallText(repo.chat.id, "正在通话的原话") }
        try {
            runCurrent()
            assertFalse(model.state.value.isBusy)
            assertNull(model.state.value.pendingMessage)
            assertTrue(model.state.value.messages.isEmpty())
        } finally { call.cancelAndJoin() }
        assertEquals(listOf("user"), repo.rows.map { it.role })
        assertTrue(model.state.value.messages.isEmpty())
    }

    @Test fun streamingFirstSentenceDoesNotPublishAnUnfinishedAssistantOrEraseTheDraft() = model { model, repo ->
        val gate = CompletableDeferred<Unit>(); val heard = mutableListOf<String>()
        model.draft("还没写完")
        repo.streamAnswer = { emit -> emit("先陪着你。"); gate.await(); emit("慢慢说。"); "先陪着你。慢慢说。" }
        val call = launch { model.streamCallText(repo.chat.id, "下班啦").toList(heard) }
        try {
            runCurrent()
            assertEquals(listOf("先陪着你。"), heard)
            assertFalse(model.state.value.isBusy)
            assertTrue(repo.rows.none { it.role == "assistant" })
            gate.complete(Unit); call.join()
            assertEquals(listOf("先陪着你。", "慢慢说。"), heard)
            assertTrue(model.state.value.messages.isEmpty())
            assertEquals("还没写完", model.state.value.draft)
        } finally { gate.complete(Unit); call.cancelAndJoin() }
    }
    @Test fun cancellingAStreamKeepsOnlyTheAlreadyCommittedOriginals() = model { model, repo ->
        val heard = mutableListOf<String>()
        repo.streamAnswer = { emit -> emit("开头。"); awaitCancellation() }
        val call = launch { model.streamCallText(repo.chat.id, "告诉你今天的事").toList(heard) }
        try {
            runCurrent(); assertEquals(listOf("开头。"), heard)
            call.cancelAndJoin(); advanceUntilIdle()
            assertTrue(model.state.value.messages.isEmpty())
            assertNull(model.state.value.busy); assertNull(model.state.value.error)
        } finally { call.cancelAndJoin() }
    }
    private fun model(test: suspend TestScope.(ChatViewModel, CallRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = CallRepository()
            val model = ChatViewModel(repo, DocumentReader(Application()))
            advanceUntilIdle()
            test(model, repo)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun finalUtteranceUsesSameChatOnceAndPreservesKeyboardDraft() = model { model, repo ->
        val image = ChatImageDraft(MediaAttachment("a".repeat(32), "image", MediaRules.path("a".repeat(32)),
            "image/png", "b".repeat(64), null, 0, "待发图片"))
        model.draft("尚未写完"); model.draftImage(image)
        assertEquals("陪你聊聊。", model.sendCallText(repo.chat.id, "刚刚下班了"))
        assertEquals(listOf(repo.chat.id to "刚刚下班了"), repo.calls)
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(repo.chat.id, model.state.value.selectedId)
        assertEquals(0, repo.created)
        assertEquals("尚未写完", model.state.value.draft); assertEquals(image, model.state.value.draftImage)
        assertNull(model.state.value.busy); assertNull(model.state.value.pendingMessage)
    }

    @Test fun cancelDuringProviderWaitRefreshesSavedOriginalWithoutReply() = model { model, repo ->
        repo.answer = { awaitCancellation() }
        val call = launch { model.sendCallText(repo.chat.id, "这句话已经说出口") }
        runCurrent()
        assertFalse(model.state.value.isBusy); assertNull(model.state.value.pendingMessage)
        call.cancel(); advanceUntilIdle()
        assertTrue(call.isCancelled)
        assertTrue(model.state.value.messages.isEmpty())
        assertTrue(model.state.value.messages.isEmpty())
        assertNull(model.state.value.error); assertNull(model.state.value.busy); assertNull(model.state.value.pendingMessage)
    }

    @Test fun providerFailureRefreshesOriginalAndShowsNoProviderPayload() = model { model, repo ->
        repo.answer = { throw ApiFailure(503, "raw-provider synthetic-secret https://private.invalid", "provider_unavailable") }
        try { model.sendCallText(repo.chat.id, "你好"); fail("Failure must reach the call owner") }
        catch (_: ApiFailure) { }
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals("user", repo.rows.single().role)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.error.orEmpty().contains("raw-provider"))
        assertFalse(model.state.value.error.orEmpty().contains("synthetic-secret"))
        assertNull(model.state.value.pendingMessage); assertNull(model.state.value.busy)
    }

    @Test fun rejectsWrongChatDisconnectedAndBusyCallsWithoutDroppingUtteranceSilently() = model { model, repo ->
        try { model.sendCallText("different-chat", "你好"); fail() } catch (_: IllegalStateException) { }
        repo.answer = { awaitCancellation() }
        val first = launch { model.sendCallText(repo.chat.id, "第一句") }; runCurrent()
        try { model.sendCallText(repo.chat.id, "第二句"); fail() } catch (_: IllegalStateException) { }
        assertEquals(listOf(repo.chat.id to "第一句"), repo.calls)
        first.cancel(); advanceUntilIdle()
        model.disconnect(); advanceUntilIdle()
        try { model.sendCallText(repo.chat.id, "第三句"); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, repo.calls.size)
    }

    @Test fun cancelledOldHistoryCannotClearOrOverwriteANewerCall() = model { model, repo ->
        repo.answer = { awaitCancellation() }
        val old = launch { model.sendCallText(repo.chat.id, "旧句") }; runCurrent()
        val historyGate = CompletableDeferred<Unit>(); repo.historyGate = historyGate
        old.cancel(); runCurrent()
        assertFalse("Cancellation releases the old UI operation before bounded refresh", model.state.value.isBusy)
        repo.historyGate = null
        val answerGate = CompletableDeferred<Unit>(); repo.answer = { answerGate.await(); "新回复" }
        val next = launch { model.sendCallText(repo.chat.id, "新句") }; runCurrent()
        historyGate.complete(Unit); runCurrent()
        assertFalse(model.state.value.isBusy); assertNull(model.state.value.pendingMessage)
        assertTrue("The older snapshot cannot replace a newer operation", model.state.value.messages.isEmpty())
        answerGate.complete(Unit); advanceUntilIdle()
        assertTrue(old.isCancelled); assertTrue(next.isCompleted)
        assertTrue("Call originals remain outside the text timeline", model.state.value.messages.isEmpty())
        assertEquals("新回复", repo.rows.last().content)
    }

    @Test fun cancelledOldHistoryCannotPopulateAnUnrelatedConnection() = model { model, repo ->
        repo.answer = { awaitCancellation() }
        val old = launch { model.sendCallText(repo.chat.id, "旧连接原话") }; runCurrent()
        val historyGate = CompletableDeferred<Unit>(); repo.historyGate = historyGate
        old.cancel(); runCurrent(); repo.historyGate = null
        model.serverUrl("https://different.invalid"); runCurrent()
        historyGate.complete(Unit); advanceUntilIdle()
        assertTrue(old.isCancelled); assertFalse(model.state.value.connected)
        assertNull(model.state.value.selectedId); assertTrue(model.state.value.messages.isEmpty())
        assertNull(model.state.value.busy); assertNull(model.state.value.pendingMessage)
    }

    @Test fun cancelledRefreshHasABoundedLifetimeAndCannotKeepCallBusy() = model { model, repo ->
        repo.answer = { awaitCancellation() }
        val old = launch { model.sendCallText(repo.chat.id, "取消这轮") }; runCurrent()
        repo.historyGate = CompletableDeferred()
        old.cancel(); runCurrent()
        advanceTimeBy(2_100); runCurrent()
        assertTrue("A stalled local refresh must not retain the canceled turn", old.isCompleted)
        assertTrue(old.isCancelled); assertNull(model.state.value.busy); assertNull(model.state.value.pendingMessage)
    }

    @Test fun nonCooperativeLateResultCannotPublishBeforeCancellationCleanup() = model { model, repo ->
        val answerGate = CompletableDeferred<Unit>()
        repo.persistAssistant = false
        repo.answer = { withContext(NonCancellable) { answerGate.await(); "迟到且未提交的回答" } }
        val call = launch { model.sendCallText(repo.chat.id, "打断原话") }; runCurrent()
        val historyGate = CompletableDeferred<Unit>(); repo.historyGate = historyGate
        try {
            call.cancel(); answerGate.complete(Unit); runCurrent()
            assertTrue("Canceled provider callback must never publish a late pair", model.state.value.messages.isEmpty())
            historyGate.complete(Unit); advanceUntilIdle()
            assertTrue(call.isCancelled); assertTrue(model.state.value.messages.isEmpty())
            assertTrue(model.state.value.messages.isEmpty())
        } finally {
            // A deliberate RED must still release the fake non-cooperative provider and history.
            answerGate.complete(Unit); historyGate.complete(Unit); call.cancelAndJoin()
        }
    }
}

private class CallRepository : ChatRepository {
    val binding = CompanionBinding("deepseek", "deepseek-flash")
    val chat = Conversation("a".repeat(32), "唯一聊天", binding.model, true, "2026-10-01", "2026-10-01")
    var connection: SavedConnection? = SavedConnection("https://local.zhiyu.invalid", binding.providerId, "synthetic-secret", binding.model)
    val rows = mutableListOf<Message>()
    val calls = mutableListOf<Pair<String, String>>()
    var answer: suspend () -> String = { "陪你聊聊。" }
    var streamAnswer: (suspend (suspend (String) -> Unit) -> String)? = null
    var persistAssistant = true
    var historyGate: CompletableDeferred<Unit>? = null
    var created = 0
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> {
        calls.add(id to content)
        val user = Message("user-${calls.size}", "user", content, emptyList(), "2026-10-15")
        rows.add(user)
        val assistant = Message("assistant-${calls.size}", "assistant", answer(), emptyList(), "2026-10-15")
        if (persistAssistant) rows.add(assistant)
        return user to assistant
    }
    override suspend fun sendCallMessage(id: String, content: String, onDelta: suspend (String) -> Unit): Pair<Message, Message> {
        val streaming = streamAnswer ?: return sendMessage(id, content).also { onDelta(it.second.content) }
        calls.add(id to content)
        val user = Message("user-${calls.size}", "user", content, emptyList(), "2026-10-15")
        rows.add(user)
        val assistant = Message("assistant-${calls.size}", "assistant", streaming(onDelta), emptyList(), "2026-10-15")
        if (persistAssistant) rows.add(assistant)
        return user to assistant
    }
    override suspend fun history(id: String, before: String?): MessagePage {
        val snapshot = rows.toList(); historyGate?.await()
        return MessagePage(snapshot, false, null)
    }
    override suspend fun savedBinding() = binding
    override suspend fun savedConnection() = connection
    override suspend fun providers(serverUrl: String) = listOf(Provider(binding.providerId, "DeepSeek", binding.model, emptyList(), "", ""))
    override suspend fun conversations() = listOf(chat)
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation { created++; return chat }
    override suspend fun documents() = emptyList<Document>()
    override suspend fun logout() { connection = null }
    override suspend fun forgetLocalConnection() { connection = null }
    override suspend fun messages(id: String): List<Message> = error("Only bounded history is permitted")
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) = error("unused")
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation = error("unused")
    override suspend fun changeRag(id: String, enabled: Boolean): Conversation = error("unused")
    override suspend fun deleteConversation(id: String): Unit = error("unused")
    override suspend fun memory(id: String, before: String?): MemoryPage = error("unused")
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry = error("unused")
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry = error("unused")
    override suspend fun upload(file: UploadedFile): Document = error("unused")
    override suspend fun deleteDocument(id: String): Unit = error("unused")
}
