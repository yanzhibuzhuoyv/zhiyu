package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Isolated DB names, no real Key, no calendar provider, and no actual background work. */
class LocalDailyBootstrapTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "daily_bootstrap_${UUID.randomUUID()}"
    private val database = LocalChatDatabase(context, "$prefix-chat.sqlite3")
    private val daily = LocalDailyRepository(context, "$prefix-daily.sqlite3")
    private val store = TestCredentials()
    private val now = Instant.parse("2026-10-09T15:59:59Z")
    private val provider = object : DirectProviderClient() {
        override suspend fun validate(providerId: String, apiKey: String, model: String) = Unit
        override suspend fun complete(providerId: String, apiKey: String, model: String,
            messages: List<DirectChatMessage>, maxTokens: Int): String = throw AssertionError("Bootstrap must not call chat or image APIs")
    }
    private fun repository() = LocalChatRepository(context, store, provider, database = database, daily = daily, dailyNow = { now })
    @After fun cleanup() {
        database.close(); daily.close()
        context.deleteDatabase("$prefix-chat.sqlite3"); context.deleteDatabase("$prefix-daily.sqlite3")
    }
    @Test fun successfulKeyLoginCreatesTodaysPlanBeforeAnyMessageAndRestorePreservesBoth() = runBlocking {
        val repo = repository()
        repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic", "deepseek-flash", null)
        val row = daily.readableDatabase.rawQuery("SELECT chat,payload,sha FROM days", null).use {
            assertTrue(it.moveToFirst()); Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        val chat = repo.conversations().single()
        assertEquals(chat.id, row.first)
        assertTrue(repo.messages(chat.id).isEmpty())
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        val user = database.append(owner, chat.id, "user", "这句原话必须保留")
        assertEquals(chat.id, repository().conversations().single().id)
        assertEquals(listOf(user), repo.messages(chat.id))
        daily.readableDatabase.rawQuery("SELECT payload,sha FROM days WHERE chat=?", arrayOf(chat.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(row.second, it.getString(0)); assertEquals(row.third, it.getString(1))
        }
    }
    @Test fun rejectedKeyNeverCreatesAPlanOrRelationship() = runBlocking {
        val reject = object : DirectProviderClient() {
            override suspend fun validate(providerId: String, apiKey: String, model: String): Unit = throw ApiFailure(401, "synthetic rejection")
        }
        val repo = LocalChatRepository(context, store, reject, database = database, daily = daily, dailyNow = { now })
        try { repo.login(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic", "deepseek-flash", null); fail() }
        catch (_: ApiFailure) { }
        assertNull(store.readBinding())
        daily.readableDatabase.rawQuery("SELECT COUNT(*) FROM days", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    private class TestCredentials : CredentialStore {
        private var saved: SavedConnection? = null
        private var bound: CompanionBinding? = null
        override fun read() = saved
        override fun write(connection: SavedConnection) { saved = connection }
        override fun clear() { saved = null }
        override fun readBinding() = bound
        override fun writeBinding(binding: CompanionBinding) { bound = binding }
    }
}
