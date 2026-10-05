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
    private fun runModel(empty: Boolean = false, block: suspend TestScope.(ChatViewModel, CompanionRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CompanionRepository()
            if (empty) repository.chats = emptyList()
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
}

private class CompanionRepository : ChatRepository {
    val binding = CompanionBinding("deepseek", "bound-model")
    var connection: SavedConnection? = SavedConnection("https://host", binding.providerId, "test-key", binding.model)
    var chats = listOf(chat("newer", "2026-10-02"), chat("other", "2026-09-01", "another-model"), chat("original", "2026-10-01"))
    val historyCalls = mutableListOf<Pair<String, String?>>()
    val sentTo = mutableListOf<String>()
    var created = 0
    private fun chat(id: String, date: String, model: String = binding.model) = Conversation(id, "与你", model, true, date, date)
    override suspend fun savedBinding() = binding
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
        return MessagePage(List(100) { Message("$prefix-$it", if (it % 2 == 0) "user" else "assistant", "test-$it", emptyList(), "2026-10-01") }, before == null, if (before == null) "cursor" else null)
    }
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> {
        sentTo.add(id)
        return Message("new-user", "user", content, emptyList(), "now") to Message("new-ai", "assistant", "记得", emptyList(), "now")
    }
    override suspend fun documents() = emptyList<Document>()
    override suspend fun upload(file: UploadedFile): Document = error("Unused")
    override suspend fun deleteDocument(id: String): Unit = error("Unused")
}
