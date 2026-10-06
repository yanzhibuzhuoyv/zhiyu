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
class MemoryPanelTest {
    private fun runModel(block: suspend TestScope.(ChatViewModel, MemoryRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = MemoryRepository()
            val vm = ChatViewModel(repo, DocumentReader(Application()))
            advanceUntilIdle()
            block(vm, repo)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun memoryPagesStayBoundedAndKeepTheSingleBoundConversation() = runModel { vm, repo ->
        val binding = vm.state.value.binding
        vm.openMemory(); advanceUntilIdle()
        assertTrue(vm.state.value.memory.open)
        assertEquals(50, vm.state.value.memory.entries.size)
        vm.olderMemory(); advanceUntilIdle()
        assertEquals(listOf(null, "before"), repo.pageCalls)
        assertEquals(50, vm.state.value.memory.entries.size)
        assertEquals("old-0", vm.state.value.memory.entries.first().messageId)
        assertEquals("original", vm.state.value.selectedId)
        assertEquals(binding, vm.state.value.binding)
        assertEquals(0, repo.created)
    }
    @Test fun longPressReadsOneExactMessageWithoutDownloadingOlderPages() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        assertEquals(listOf("recent-0"), repo.oneCalls)
        assertTrue(repo.pageCalls.isEmpty())
        assertEquals("recent-0", vm.state.value.memory.selected?.messageId)
        assertEquals("想吃苹果🍎\u0000e\u0301", vm.state.value.memory.selected?.text)
    }
    @Test fun updateUsesReadRevisionAndLeavesRawChatMessageUntouched() = runModel { vm, repo ->
        val messages = vm.state.value.messages
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        vm.changeMemoryTier("permanent"); advanceUntilIdle()
        assertEquals(4, repo.changes.single().expectedRevision)
        assertEquals("permanent", vm.state.value.memory.selected?.tier)
        assertEquals(5, vm.state.value.memory.selected?.revision)
        assertEquals(messages, vm.state.value.messages)
    }
    @Test fun conflictShowsReasonRefreshesMetadataAndDoesNotSilentlyRetry() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        repo.conflict = true
        vm.changeMemoryTier("long"); advanceUntilIdle()
        assertEquals(1, repo.changes.size)
        assertEquals(listOf("recent-0", "recent-0"), repo.oneCalls)
        assertEquals(9, vm.state.value.memory.selected?.revision)
        assertTrue(vm.state.value.error!!.contains("记忆已被更新"))
        assertEquals("mid", vm.state.value.memory.selected?.tier)
    }
    @Test fun protectedArchiveErrorIsVisibleAndDoesNotChangeMessageState() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        repo.failure = ApiFailure(409, "永久记忆不可归档")
        vm.archiveMemory(); advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("永久记忆不可归档"))
        assertEquals("active", vm.state.value.memory.selected?.indexState)
    }
    @Test fun automaticResetArchiveAndRestoreOnlyChangeMetadata() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        vm.changeMemoryTier("auto"); advanceUntilIdle()
        assertEquals("auto", repo.changes.last().tier)
        vm.archiveMemory(); advanceUntilIdle()
        assertEquals("cold", vm.state.value.memory.selected?.indexState)
        vm.restoreMemory(); advanceUntilIdle()
        assertEquals("active", vm.state.value.memory.selected?.indexState)
    }
    @Test fun closingWhileReadingDoesNotReopenOrLeakTheResult() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0")
        vm.closeMemory()
        advanceUntilIdle()
        assertFalse(vm.state.value.memory.open)
        assertNull(vm.state.value.memory.selected)
    }
    @Test fun independentConfirmationUsesTheServerEligibility() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        vm.confirmIndependentMemory(); advanceUntilIdle()
        assertTrue(repo.changes.single().confirmIndependent)
        assertFalse(vm.state.value.memory.selected!!.canConfirmIndependent)
        vm.confirmIndependentMemory(); advanceUntilIdle()
        assertEquals(1, repo.changes.size)
    }
    @Test fun expiredSessionClosesMemoryAndClearsItsOriginalExcerpts() = runModel { vm, repo ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        repo.failure = ApiFailure(401, "连接凭据已失效")
        vm.changeMemoryTier("long"); advanceUntilIdle()
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.memory.open)
        assertNull(vm.state.value.memory.selected)
        assertTrue(vm.state.value.memory.entries.isEmpty())
        assertEquals(repo.binding, vm.state.value.binding)
    }
    @Test fun changingDevelopmentServerDoesNotCarryOldMemoryToAnotherBackend() = runModel { vm, _ ->
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        vm.serverUrl("http://10.0.2.2:8900"); advanceUntilIdle()
        assertFalse(vm.state.value.memory.open)
        assertNull(vm.state.value.memory.selected)
    }
    @Test fun metadataFromADifferentMessageIsRejectedInsteadOfDisplayed() = runModel { vm, repo ->
        repo.wrongMessage = true
        vm.openMessageMemory("recent-0"); advanceUntilIdle()
        assertNull(vm.state.value.memory.selected)
        assertTrue(vm.state.value.error!!.contains("记忆来源与所选消息不一致"))
    }
}

private class MemoryRepository : ChatRepository {
    val binding = CompanionBinding("demo", "bound", CompanionProfile())
    val chat = Conversation("original", "与你", "bound", true, "now", "now", binding.profile)
    val pageCalls = mutableListOf<String?>()
    val oneCalls = mutableListOf<String>()
    val changes = mutableListOf<MemoryChange>()
    var created = 0
    var conflict = false
    var failure: ApiFailure? = null
    var wrongMessage = false
    private var entry = MemoryEntry("recent-0", "user", "想吃苹果🍎\u0000e\u0301", "2026-10-06T09:00:00Z", 11,
        "mid", "active", 4, false, "default", null, true)
    override suspend fun memory(id: String, before: String?): MemoryPage {
        check(id == chat.id); pageCalls.add(before)
        return MemoryPage(List(50) { entry.copy(messageId = "${if (before == null) "recent" else "old"}-$it") }, before == null,
            if (before == null) "before" else null, true)
    }
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry {
        check(id == chat.id); oneCalls.add(messageId)
        if (conflict) entry = entry.copy(revision = 9)
        return entry.copy(messageId = if (wrongMessage) "another" else messageId)
    }
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry {
        check(id == chat.id); changes.add(change)
        failure?.let { throw it }
        if (conflict) throw ApiFailure(409, "记忆已被更新，请刷新后重试")
        entry = entry.copy(tier = change.tier?.takeUnless { it == "auto" } ?: entry.tier,
            manualTier = change.tier?.takeUnless { it == "auto" }, indexState = change.indexState ?: entry.indexState,
            revision = entry.revision + 1, canConfirmIndependent = !change.confirmIndependent)
        return entry
    }
    override suspend fun savedBinding() = binding
    override suspend fun savedConnection() = SavedConnection("https://host", "demo", "test-key", "bound")
    override suspend fun providers(serverUrl: String) = listOf(Provider("demo", "演示", "bound", emptyList(), "", ""))
    override suspend fun conversations() = listOf(chat)
    override suspend fun history(id: String, before: String?) = MessagePage(listOf(Message("recent-0", "user", "原文🍎", emptyList(), "now")), false, null)
    override suspend fun documents() = emptyList<Document>()
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation { created++; return chat }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) = Unit
    override suspend fun logout() = Unit
    override suspend fun forgetLocalConnection() = Unit
    override suspend fun changeProfile(id: String, profile: CompanionProfile?) = chat
    override suspend fun changeRag(id: String, enabled: Boolean) = chat
    override suspend fun deleteConversation(id: String): Unit = error("Must not delete the chat")
    override suspend fun messages(id: String): List<Message> = error("Must not fetch all originals")
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> = error("Unused")
    override suspend fun upload(file: UploadedFile): Document = error("Unused")
    override suspend fun deleteDocument(id: String): Unit = error("Unused")
}
