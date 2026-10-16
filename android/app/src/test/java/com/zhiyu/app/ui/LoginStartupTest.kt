package com.zhiyu.app.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.zhiyu.app.data.DocumentReader
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginStartupTest {
    @Test fun firstLaunchLoadsCompaniesWithoutAConnectionButton() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CatalogRepository()
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            assertEquals(listOf(model.state.value.serverUrl), repository.urls)
            assertEquals("deepseek", model.state.value.providerId)
            assertEquals("test-model", model.state.value.model)
            assertFalse(model.state.value.restoring)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun changingDevelopmentServerClearsKeyAndLoadsTheNewCatalog() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CatalogRepository()
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            model.apiKey("private-key")
            model.serverUrl("http://10.0.2.2:8900")
            advanceUntilIdle()
            assertEquals("http://10.0.2.2:8900", repository.urls.last())
            assertEquals("", model.state.value.apiKey)
            assertEquals(1, repository.cleared)
            assertEquals("deepseek", model.state.value.providerId)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun catalogRetryWithoutASavedKeyKeepsTheUsersUnconfirmedInputAndDoesNotLogIn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = CatalogRepository()
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            model.apiKey("unconfirmed-key")
            model.setProfile(CompanionProfile(name = "新设定"))
            advanceUntilIdle()
            model.loadProviders()
            advanceUntilIdle()
            assertFalse(model.state.value.connected)
            assertFalse(model.state.value.canRestoreConnection)
            assertEquals("unconfirmed-key", model.state.value.apiKey)
            assertEquals("新设定", model.state.value.profile?.name)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun retryAfterOfflineStartupRestoresTheSameChatWithoutNewLoginOrClearingTheKey() = runRecovery { model, repository ->
        assertFalse(model.state.value.connected)
        assertTrue(model.state.value.canRestoreConnection)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals("saved-key", model.state.value.apiKey)
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals("original", model.state.value.selectedId)
        assertEquals(repository.originalMessages, model.state.value.messages)
        assertEquals("saved-key", model.state.value.apiKey)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals(0, repository.logins)
        assertEquals(0, repository.cleared)
        assertEquals(0, repository.created)
        assertNull(model.state.value.error)
    }

    @Test fun retryAlsoRecoversWhenTheCatalogLoadedButHistoryWasUnavailable() = runRecovery(historyOffline = true) { model, repository ->
        assertFalse(model.state.value.connected)
        assertEquals("original", model.state.value.selectedId)
        repository.historyOffline = false
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals(repository.originalMessages, model.state.value.messages)
        assertEquals(0, repository.logins)
        assertEquals(0, repository.created)
    }

    @Test fun legacyProfileIsUpdatedOnlyAfterTheExistingChatIsSuccessfullyRecovered() = runRecovery { model, repository ->
        repository.binding = repository.binding.copy(profile = null, profileConfigured = false)
        model.loadProviders()
        advanceUntilIdle()
        assertFalse(model.state.value.binding!!.profileConfigured)
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertTrue(model.state.value.binding!!.profileConfigured)
        assertEquals("原来的恋人", model.state.value.binding!!.profile?.name)
        assertEquals("original", model.state.value.selectedId)
        assertEquals(0, repository.created)
    }

    @Test fun customTrainingSavedWhileOfflineIsAppliedWhenTheOriginalChatRecovers() = runRecovery { model, repository ->
        repository.useCustomProfile()
        model.loadProviders()
        advanceUntilIdle()
        val edited = repository.binding.profile!!.copy(instructions = "叫我小舟")
        model.setProfile(edited)
        advanceUntilIdle()
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals("original", model.state.value.selectedId)
        assertEquals(edited, model.state.value.profile)
        assertEquals(edited, model.state.value.binding!!.profile)
        assertEquals(1, repository.profileChanges)
        assertEquals(0, repository.logins)
    }

    @Test fun customTrainingSurvivesAHistoryFailureBeforeRecoveryCanSaveIt() = runRecovery { model, repository ->
        repository.useCustomProfile()
        model.loadProviders()
        advanceUntilIdle()
        val edited = repository.binding.profile!!.copy(instructions = "叫我小舟")
        model.setProfile(edited)
        advanceUntilIdle()
        repository.offline = false
        repository.historyOffline = true
        model.loadProviders()
        advanceUntilIdle()
        assertFalse(model.state.value.connected)
        assertEquals(edited, model.state.value.profile)
        assertEquals(0, repository.profileChanges)
        repository.historyOffline = false
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals(edited, model.state.value.profile)
        assertEquals(edited, model.state.value.binding!!.profile)
        assertEquals(1, repository.profileChanges)
    }

    @Test fun retryLeavesAnUnavailableConnectionUnconnectedAndKeepsItsFixedChoice() = runRecovery { model, repository ->
        model.loadProviders()
        advanceUntilIdle()
        assertFalse(model.state.value.connected)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals("saved-key", model.state.value.apiKey)
        assertTrue(model.state.value.error!!.contains("无法连接后端"))
        assertEquals(0, repository.cleared)
        assertEquals(0, repository.logins)
    }

    @Test fun providerRejectionDuringAChatDoesNotDiscardTheChatAsAnExpiredSession() = runRecovery { model, repository ->
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        repository.sendFailure = ApiFailure(401, "synthetic-provider-payload saved-key 服务商拒绝了 API Key", "provider_auth_failed")
        model.draft("晚上好")
        model.send()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals("original", model.state.value.selectedId)
        assertFailedOriginalIsRetainedWithoutPrivateDiagnostics(model.state.value)
        assertEquals(repository.originalMessages, model.state.value.messages)
        assertEquals("saved-key", model.state.value.apiKey)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals(0, repository.logins)
        assertEquals(1, repository.sendCalls)
    }

    @Test fun unexpectedLocalFailureDoesNotTellPhoneUsersToCheckAComputerBackend() = runRecovery { model, repository ->
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        repository.sendFailure = RuntimeException("internal diagnostic")
        model.draft("晚上好")
        model.send()
        advanceUntilIdle()
        assertFailedOriginalIsRetainedWithoutPrivateDiagnostics(model.state.value)
        assertTrue(model.state.value.connected)
        assertEquals(repository.originalMessages, model.state.value.messages)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals(1, repository.sendCalls)
    }

    @Test fun failedLogoutDoesNotClaimThatTheKeyWasAlreadyCleared() = runRecovery { model, repository ->
        repository.offline = false
        model.loadProviders()
        advanceUntilIdle()
        repository.logoutFailure = IllegalStateException("local storage failure")
        model.disconnect()
        advanceUntilIdle()
        assertEquals("退出未完成，请重试；恋人绑定和聊天会保留", model.state.value.error)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals(0, repository.cleared)
    }

    @Test fun repeatedRetryWhileBusyDoesNotStartAnotherRecovery() = runRecovery { model, repository ->
        repository.offline = false
        repository.catalogGate = CompletableDeferred()
        model.loadProviders()
        runCurrent()
        val calls = repository.catalogCalls
        model.loadProviders()
        runCurrent()
        assertEquals(calls, repository.catalogCalls)
        repository.catalogGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
    }

    @Test fun localMigrationNoticeIsDisplayedWithoutReplacingTheChatOrItsBinding() = runRecovery { model, repository ->
        repository.offline = false
        repository.migrationNotice = "旧聊天尚未导入，原数据仍保留在原设备。"
        model.loadProviders()
        advanceUntilIdle()
        assertTrue(model.state.value.connected)
        assertEquals(repository.migrationNotice, model.state.value.notice)
        assertEquals(repository.originalMessages, model.state.value.messages)
        assertEquals(repository.binding, model.state.value.binding)
        model.dismissError()
        assertNull(model.state.value.notice)
        assertEquals(repository.originalMessages, model.state.value.messages)
    }

    @Test fun cancellingRecoveryDoesNotBecomeALoginErrorOrDeleteCredentials() = runRecovery { model, repository ->
        repository.offline = false
        repository.catalogGate = CompletableDeferred()
        model.loadProviders()
        runCurrent()
        model.viewModelScope.cancel()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals("saved-key", model.state.value.apiKey)
        assertEquals(repository.binding, model.state.value.binding)
        assertEquals(0, repository.cleared)
    }

    private fun assertFailedOriginalIsRetainedWithoutPrivateDiagnostics(state: AppState) {
        assertEquals("", state.draft)
        val retained = state.pendingOutgoing.single()
        assertEquals("晚上好", retained.content)
        assertEquals(OutgoingPhase.FAILED, retained.phase)
        assertNull(retained.submittedMessageId)
        assertFalse(state.error.isNullOrBlank())
        assertTrue(state.error!!.contains("重试"))
        assertFalse(retained.problem.isNullOrBlank())
        val displayed = state.error + "\n" + retained.problem
        listOf("saved-key", "synthetic-provider-payload", "internal diagnostic", "电脑", "后端", "backend").forEach { privateText ->
            assertFalse("Send failure must not expose $privateText", displayed.contains(privateText, ignoreCase = true))
        }
    }

    private fun runRecovery(historyOffline: Boolean = false, block: suspend TestScope.(ChatViewModel, RecoveryRepository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = RecoveryRepository().apply {
                offline = !historyOffline
                this.historyOffline = historyOffline
            }
            val model = ChatViewModel(repository, DocumentReader(Application()))
            advanceUntilIdle()
            block(model, repository)
        } finally { Dispatchers.resetMain() }
    }
}

private open class CatalogRepository : ChatRepository {
    override suspend fun savedBinding(): CompanionBinding? = null
    val urls = mutableListOf<String>()
    var cleared = 0
    override suspend fun savedConnection(): SavedConnection? = null
    override suspend fun providers(serverUrl: String): List<Provider> {
        urls.add(serverUrl)
        return listOf(Provider("deepseek", "DeepSeek", "test-model", emptyList(), "", ""))
    }
    override suspend fun forgetLocalConnection() { cleared++ }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?): Unit = error("Unused")
    override suspend fun logout(): Unit = error("Unused")
    override suspend fun conversations(): List<Conversation> = error("Unused")
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation = error("Unused")
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation = error("Unused")
    override suspend fun changeRag(id: String, enabled: Boolean): Conversation = error("Unused")
    override suspend fun deleteConversation(id: String): Unit = error("Unused")
    override suspend fun messages(id: String): List<Message> = error("Unused")
    override suspend fun history(id: String, before: String?): MessagePage = error("Unused")
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> = error("Unused")
    override suspend fun documents(): List<Document> = error("Unused")
    override suspend fun memory(id: String, before: String?): MemoryPage = error("Unused")
    override suspend fun messageMemory(id: String, messageId: String): MemoryEntry = error("Unused")
    override suspend fun changeMemory(id: String, messageId: String, change: MemoryChange): MemoryEntry = error("Unused")
    override suspend fun upload(file: UploadedFile): Document = error("Unused")
    override suspend fun deleteDocument(id: String): Unit = error("Unused")
}

private class RecoveryRepository : CatalogRepository() {
    var migrationNotice: String? = null
    override suspend fun connectionNotice() = migrationNotice
    var binding = CompanionBinding("deepseek", "fixed-model", CompanionProfile(name = "原来的恋人"))
    private val connection = SavedConnection("https://saved-host", binding.providerId, "saved-key", binding.model)
    private var chat = Conversation("original", "与你", binding.model, true, "2026-10-01", "2026-10-01", binding.profile)
    val originalMessages = listOf(Message("original-message", "assistant", "回来了", emptyList(), "2026-10-01"))
    var offline = true
    var historyOffline = false
    var catalogGate: CompletableDeferred<Unit>? = null
    var catalogCalls = 0
    var logins = 0
    var created = 0
    var sendFailure: Exception? = null
    var sendCalls = 0
    var logoutFailure: Exception? = null
    var profileChanges = 0
    fun useCustomProfile() {
        val custom = CompanionProfile(mode = "custom", name = "原来的恋人", instructions = "说话自然一些")
        binding = binding.copy(profile = custom)
        chat = chat.copy(profile = custom)
    }
    override suspend fun savedConnection() = connection
    override suspend fun logout() { logoutFailure?.let { throw it }; cleared++ }
    override suspend fun savedBinding() = binding
    override suspend fun providers(serverUrl: String): List<Provider> {
        catalogCalls++
        catalogGate?.await()
        if (offline) throw ApiFailure(0, "无法连接后端")
        return listOf(Provider(binding.providerId, "DeepSeek", "default-model", emptyList(), "", ""))
    }
    override suspend fun login(serverUrl: String, providerId: String, apiKey: String, model: String, profile: CompanionProfile?) { logins++ }
    override suspend fun conversations(): List<Conversation> {
        if (!binding.profileConfigured) binding = binding.copy(profile = chat.profile, profileConfigured = true)
        return listOf(chat)
    }
    override suspend fun history(id: String, before: String?): MessagePage {
        if (historyOffline) throw ApiFailure(0, "无法连接后端")
        check(id == chat.id)
        return MessagePage(originalMessages, false, null)
    }
    override suspend fun createConversation(model: String, useRag: Boolean, profile: CompanionProfile?): Conversation { created++; return chat }
    override suspend fun changeProfile(id: String, profile: CompanionProfile?): Conversation {
        check(id == chat.id)
        profileChanges++
        chat = chat.copy(profile = profile)
        binding = binding.copy(profile = profile)
        return chat
    }
    override suspend fun documents() = emptyList<Document>()
    override suspend fun sendMessage(id: String, content: String): Pair<Message, Message> {
        sendCalls++
        throw sendFailure ?: error("Unused")
    }
}
