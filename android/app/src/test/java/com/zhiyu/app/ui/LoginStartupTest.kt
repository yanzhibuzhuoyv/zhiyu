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
}

private class CatalogRepository : ChatRepository {
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
    override suspend fun upload(file: UploadedFile): Document = error("Unused")
    override suspend fun deleteDocument(id: String): Unit = error("Unused")
}
