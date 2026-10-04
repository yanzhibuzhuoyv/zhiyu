package com.zhiyu.app.domain

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class SessionCoordinatorTest {
    private class MemoryStore : CredentialStore {
        var value: SavedConnection? = null
        override fun read() = value
        override fun write(connection: SavedConnection) { value = connection }
        override fun clear() { value = null }
    }
    private class FakeAuth : AuthService {
        var count = 0
        var reject = false
        override suspend fun authenticate(connection: SavedConnection): AuthSession {
            if (reject) throw ApiFailure(401, "Key 无效")
            count++
            return AuthSession("token-$count", "deepseek", connection.model, 5000)
        }
    }
    @Test fun validSessionDoesNotUseAnotherPaidValidation() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val manager = SessionCoordinator(store, auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        assertEquals("token-1", manager.token())
        assertEquals(1, auth.count)
    }
    @Test fun expiryReauthenticatesAndPersistsToken() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model", AuthSession("old", "deepseek", "model", 999)) }
        val manager = SessionCoordinator(store, FakeAuth()) { 1000 }
        assertEquals("token-1", manager.token())
        assertEquals("token-1", store.value?.session?.token)
    }
    @Test fun restart401RefreshesOnceBeforeRetry() = runTest {
        val auth = FakeAuth()
        val manager = SessionCoordinator(MemoryStore(), auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        var calls = 0
        val result = manager.authorized { token ->
            calls++
            if (token == "token-1") throw ApiFailure(401, "会话失效")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(2, auth.count)
    }
    @Test fun failedRefreshHasClearFailureAndKeepsCredentials() = runTest {
        val auth = FakeAuth()
        val store = MemoryStore()
        val manager = SessionCoordinator(store, auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        auth.reject = true
        try {
            manager.authorized<Unit> { throw ApiFailure(401, "过期") }
            fail("Expected failure")
        } catch (error: ApiFailure) {
            assertTrue(error.message!!.contains("重新验证"))
        }
        assertEquals("key", store.value?.apiKey)
    }
    @Test fun failedNewLoginCannotReplaceWorkingConnection() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val manager = SessionCoordinator(store, auth) { 1000 }
        manager.login("https://first", "deepseek", "key", "model")
        auth.reject = true
        try { manager.login("https://second", "deepseek", "bad", "model") } catch (_: ApiFailure) { }
        assertEquals("https://first", store.value?.serverUrl)
    }
    @Test fun clearRemovesKeyAndSession() = runTest {
        val store = MemoryStore()
        val manager = SessionCoordinator(store, FakeAuth()) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        manager.clear()
        assertNull(store.value)
    }
    @Test fun credentialsNeverRenderKeyOrToken() {
        val connection = SavedConnection("https://host", "deepseek", "super-secret", "model", AuthSession("bearer-secret", "deepseek", "model", 5000))
        assertFalse(connection.toString().contains("super-secret"))
        assertFalse(connection.toString().contains("bearer-secret"))
        assertFalse(connection.session.toString().contains("bearer-secret"))
    }
    @Test fun concurrentExpiredRequestsOnlyValidateKeyOnce() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model") }
        var logins = 0
        val auth = object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession {
                delay(10)
                logins++
                return AuthSession("new-token", "deepseek", "model", 5000)
            }
        }
        val manager = SessionCoordinator(store, auth) { 1000 }
        assertEquals(listOf("new-token", "new-token", "new-token"), List(3) { async { manager.token() } }.awaitAll())
        assertEquals(1, logins)
    }
    @Test fun rejectsOnlyRetryOnceEvenWhenReplacementTokenFails() = runTest {
        val auth = FakeAuth()
        val manager = SessionCoordinator(MemoryStore(), auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        var calls = 0
        try { manager.authorized<Unit> { calls++; throw ApiFailure(401, "拒绝") }; fail("Expected error") } catch (_: ApiFailure) { }
        assertEquals(2, calls)
        assertEquals(2, auth.count)
    }
    @Test fun cancellationCannotBeConvertedIntoAnAuthError() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model") }
        val manager = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession { throw CancellationException("cancelled") }
        })
        try { manager.token(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertNull(store.value?.session)
    }
}
