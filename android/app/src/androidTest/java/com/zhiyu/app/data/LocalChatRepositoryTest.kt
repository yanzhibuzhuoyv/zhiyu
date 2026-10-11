package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Fake official client, isolated SQLite and synthetic Keys; exercises the real local repository. */
class LocalChatRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "local_repository_test_${UUID.randomUUID()}.sqlite3"
    private val database = LocalChatDatabase(context, name)
    private val store = SyntheticStore()
    private val client = SyntheticClient()
    private val repo = LocalChatRepository(context, store, client, { "本地已授权天气：小雨，更新时间为刚才。" }, database)
    private val profile = CompanionProfile(mode = "custom", name = "合成恋人", instructions = "喜欢安静地说话")
    @After fun cleanup() { ProactiveForeground.setVisible(false); database.close(); context.deleteDatabase(name) }
    private suspend fun connect(key: String = "synthetic-key", selected: CompanionProfile = profile) {
        repo.login(LocalChatRepository.LOCAL_URL, "deepseek", key, "deepseek-flash", selected)
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected operation to fail")
    }
    @Test fun providerListNeedsNoBackendAndRejectedKeyDoesNotBindTheLover() = runBlocking {
        assertTrue(repo.providers("https://unused.invalid").any { it.id == "deepseek" })
        assertEquals(0, client.validations)
        client.validationError = ApiFailure(401, "合成 Key 拒绝", "provider_auth_failed")
        assertTrue(failure { connect() } is ApiFailure)
        assertNull(store.read()); assertNull(store.readBinding())
        assertNull(repo.savedBinding())
    }
    @Test fun keyRenewalPreservesTheUniqueConversationAndUsesUpdatedCustomPersona() = runBlocking {
        connect()
        val conversation = repo.conversations().single()
        val pair = repo.sendMessage(conversation.id, "我刚下班，好困呀")
        val edited = profile.copy(instructions = "新设定：说话更加直率")
        connect("replacement-synthetic-key", edited)
        assertEquals(conversation.id, repo.conversations().single().id)
        assertEquals(edited, repo.conversations().single().profile)
        assertEquals(listOf(pair.first, pair.second), repo.messages(conversation.id))
        assertEquals("replacement-synthetic-key", store.read()?.apiKey)
        repo.sendMessage(conversation.id, "你今天怎么样？")
        assertTrue(client.lastMessages.first().content.contains("新设定"))
        assertEquals(2, client.calls)
    }
    @Test fun fixedCompanyModelAndPresetCannotBeChangedAfterValidation() = runBlocking {
        val fixed = CompanionProfile(style = "steady")
        connect(selected = fixed)
        assertTrue(failure { repo.login("unused", "qwen", "synthetic-key", "qwen-plus", fixed) } is IllegalArgumentException)
        assertTrue(failure { repo.login("unused", "deepseek", "synthetic-key", "other-model", fixed) } is IllegalArgumentException)
        assertTrue(failure { connect(selected = fixed.copy(style = "playful")) } is IllegalArgumentException)
        assertEquals(1, client.validations)
        assertEquals(fixed, repo.conversations().single().profile)
    }
    @Test fun eachTurnHasOneCallAndOneCurrentUserMessageWithoutInventedFailureReplies() = runBlocking {
        connect()
        val chat = repo.conversations().single()
        val pair = repo.sendMessage(chat.id, "你知道我的生日吗")
        assertEquals(1, client.calls)
        assertEquals(1, client.lastMessages.count { it.role == "user" && it.content == pair.first.content })
        assertTrue(client.lastMessages.first().content.contains("我没印象了"))
        assertTrue(client.lastMessages.first().content.contains("小雨"))
        assertEquals("合成完整回复。", pair.second.content)
        client.completionError = ApiFailure(401, "合成 Key 拒绝", "provider_auth_failed")
        assertTrue(failure { repo.sendMessage(chat.id, "失败时也保留我的这句原话") } is ApiFailure)
        val history = repo.messages(chat.id)
        assertEquals(3, history.size)
        assertEquals("user", history.last().role)
        assertEquals("失败时也保留我的这句原话", history.last().content)
        assertEquals("synthetic-key", store.read()?.apiKey)
    }
    @Test fun cancellationKeepsTheRealUserOriginalAndNeverPersistsAnAssistant() = runBlocking {
        connect()
        val chat = repo.conversations().single()
        client.gate = CompletableDeferred()
        val task = launch { repo.sendMessage(chat.id, "等待期间取消") }
        withTimeout(10000) { client.started.await() }
        task.cancelAndJoin()
        assertEquals(listOf("等待期间取消"), repo.messages(chat.id).map { it.content })
        assertEquals(listOf("user"), repo.messages(chat.id).map { it.role })
    }
    @Test fun proactiveReplayIsIdempotentAcrossRepositoryInstancesAndCreatesNoUserMessage() = runBlocking {
        ProactiveForeground.setVisible(false)
        connect()
        val chat = repo.conversations().single()
        val request = UUID.randomUUID().toString()
        val first = repo.sendProactive(chat.id, request)
        val other = LocalChatRepository(context, store, client, database = database)
        assertEquals(first, other.sendProactive(chat.id, request))
        assertEquals(first, other.proactiveResult(chat.id, request))
        assertEquals(1, client.calls)
        assertEquals("proactive", first.origin)
        assertEquals(listOf(first), repo.messages(chat.id))
        assertTrue(repo.proactivePolicyHistory(chat.id).all { it.role == "assistant" })
    }
    @Test fun foregroundReturnCancelsHungBackgroundGenerationAndReleasesTheChatLock() = runBlocking {
        connect()
        val chat = repo.conversations().single()
        ProactiveForeground.setVisible(false)
        client.gate = CompletableDeferred()
        val background = async { repo.sendProactive(chat.id, UUID.randomUUID().toString()) }
        withTimeout(10000) { client.started.await() }
        ProactiveForeground.setVisible(true)
        withTimeout(5000) { background.join() }
        assertTrue(background.isCancelled)
        client.gate = null
        val pair = withTimeout(5000) { repo.sendMessage(chat.id, "回到前台之后的话") }
        assertEquals(listOf(pair.first, pair.second), repo.messages(chat.id))
        assertEquals(2, client.calls)
    }
    @Test fun migratingOldCredentialsDoesNotClaimThatTheComputerHistoryIsOnThePhone() = runBlocking {
        val session = AuthSession("synthetic-session", "deepseek", "deepseek-flash", Long.MAX_VALUE)
        store.write(SavedConnection("http://127.0.0.1:8791", "deepseek", "synthetic-key", "deepseek-flash", session))
        store.writeBinding(CompanionBinding("deepseek", "deepseek-flash", profile))
        val saved = repo.savedConnection()!!
        assertEquals(LocalChatRepository.LOCAL_URL, saved.serverUrl)
        assertEquals("synthetic-key", saved.apiKey)
        assertNull(saved.session)
        assertEquals(profile, repo.savedBinding()?.profile)
        assertTrue(repo.connectionNotice()!!.contains("旧聊天尚未导入"))
        assertTrue(repo.messages(repo.conversations().single().id).isEmpty())
        assertEquals(0, client.calls + client.validations)
    }
    @Test fun asynchronousValidationCannotResurrectCredentialsClearedWhileWaiting() = runBlocking {
        store.write(SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "old-synthetic-key", "deepseek-flash"))
        client.validationGate = CompletableDeferred()
        val task = async { failure { connect() } }
        withTimeout(10000) { client.validationStarted.await() }
        store.clear()
        client.validationGate!!.complete(Unit)
        assertTrue(task.await() is IllegalStateException)
        assertNull(store.read()); assertNull(store.readBinding())
    }
    private class SyntheticStore : CredentialStore {
        private var saved: SavedConnection? = null
        private var binding: CompanionBinding? = null
        @Synchronized override fun read() = saved
        @Synchronized override fun write(connection: SavedConnection) { saved = connection }
        @Synchronized override fun clear() { saved = null }
        @Synchronized override fun readBinding() = binding
        @Synchronized override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
    private class SyntheticClient : DirectProviderClient() {
        var validations = 0; var calls = 0
        var validationError: Exception? = null; var completionError: Exception? = null
        var lastMessages = emptyList<DirectChatMessage>()
        var gate: CompletableDeferred<Unit>? = null; val started = CompletableDeferred<Unit>()
        var validationGate: CompletableDeferred<Unit>? = null; val validationStarted = CompletableDeferred<Unit>()
        override suspend fun validate(providerId: String, key: String, model: String) {
            validations++; validationStarted.complete(Unit); validationGate?.await(); validationError?.let { throw it }
        }
        override suspend fun complete(providerId: String, key: String, model: String, messages: List<DirectChatMessage>, maxTokens: Int): String {
            calls++; lastMessages = messages; started.complete(Unit); gate?.await(); completionError?.let { throw it }
            return "合成完整回复。"
        }
    }
}
