package com.zhiyu.app.domain

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class SessionCoordinatorTest {
    @Test fun providerAuthFailureDoesNotRefreshTheBackendSessionOrReplayTheRequest() = runTest {
        val auth = FakeAuth()
        val store = MemoryStore()
        val manager = SessionCoordinator(store, auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        val before = store.value
        var calls = 0
        try {
            manager.authorized<Unit> { calls++; throw ApiFailure(401, "服务商 Key 无效", "provider_auth_failed") }
            fail("Expected provider rejection")
        } catch (error: ApiFailure) { assertEquals("provider_auth_failed", error.errorCode) }
        assertEquals(1, calls)
        assertEquals(1, auth.count)
        assertEquals(before, store.value)
    }

    @Test fun explicitlyExpiredBackendSessionRefreshesOnceAndRetriesTheOriginalRequest() = runTest {
        val auth = FakeAuth()
        val manager = SessionCoordinator(MemoryStore(), auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        var calls = 0
        val result = manager.authorized { token ->
            calls++
            if (token == "token-1") throw ApiFailure(401, "会话失效", "session_expired")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(2, auth.count)
    }

    @Test fun anotherClassifiedUnauthorizedResponseDoesNotTriggerAnUnrelatedSessionRefresh() = runTest {
        val auth = FakeAuth()
        val manager = SessionCoordinator(MemoryStore(), auth) { 1000 }
        manager.login("https://host", "deepseek", "key", "model")
        var calls = 0
        try {
            manager.authorized<Unit> { calls++; throw ApiFailure(401, "其他鉴权失败", "unrelated_auth_failure") }
            fail("Expected original failure")
        } catch (error: ApiFailure) { assertEquals("unrelated_auth_failure", error.errorCode) }
        assertEquals(1, calls)
        assertEquals(1, auth.count)
    }

    @Test fun authenticationFailureKeepsItsProviderClassificationAndStoredKey() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model") }
        val manager = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession =
                throw ApiFailure(401, "服务商拒绝了 Key", "provider_auth_failed")
        }) { 1000 }
        try { manager.token(); fail("Expected authentication failure") } catch (error: ApiFailure) {
            assertEquals("provider_auth_failed", error.errorCode)
            assertFalse(error.message!!.contains("会话已失效"))
        }
        assertEquals("key", store.value?.apiKey)
        assertNull(store.value?.session)
    }

    @Test fun anOfflineKeyRefreshIsReportedAsAConnectionFailureWithoutMislabelingTheKey() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model") }
        val manager = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession = throw ApiFailure(0, "无法连接后端")
        }) { 1000 }
        try { manager.token(); fail("Expected connection failure") } catch (error: ApiFailure) {
            assertEquals(0, error.status)
            assertEquals("无法连接后端", error.message)
        }
        assertEquals("key", store.value?.apiKey)
    }

    private class MemoryStore : CredentialStore {
        var value: SavedConnection? = null
        var binding: CompanionBinding? = null
        override fun read() = value
        override fun write(connection: SavedConnection) { value = connection }
        override fun clear() { value = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
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
    @Test fun successfulChoiceSurvivesLogoutAndCannotChangeCompanyOrModel() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val manager = SessionCoordinator(store, auth)
        manager.login("https://host", "deepseek", "key", "model")
        manager.clear()
        assertEquals(CompanionBinding("deepseek", "model"), store.binding)
        assertNull(store.value)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { manager.login("https://host", "qwen", "key", "model") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { manager.login("https://host", "deepseek", "key", "other-model") }
        }
        assertEquals(1, auth.count)
    }
    @Test fun failedVerificationDoesNotBindAnAi() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth().apply { reject = true }
        val manager = SessionCoordinator(store, auth)
        try { manager.login("https://host", "deepseek", "bad", "model") } catch (_: ApiFailure) { }
        assertNull(store.binding)
        assertNull(store.value)
    }
    @Test fun legacyVerifiedSessionAdoptsItsOriginalAiAndRejectsAnAlteredConnection() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model", AuthSession("legacy", "deepseek", "model", 5000)) }
        val auth = FakeAuth()
        val manager = SessionCoordinator(store, auth) { 1000 }
        assertEquals("legacy", manager.token())
        assertEquals(CompanionBinding("deepseek", "model", profileConfigured = false), store.binding)
        store.value = store.value!!.copy(providerId = "qwen")
        try { manager.token(); fail("Expected identity mismatch") } catch (_: IllegalArgumentException) { }
        assertEquals(0, auth.count)
    }
    @Test fun refreshCannotAcceptADifferentModelFromTheServer() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model") }
        val manager = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection) = AuthSession("bad", "deepseek", "different", 5000)
        })
        try { manager.token(); fail("Expected identity mismatch") } catch (_: IllegalArgumentException) { }
        assertNull(store.value!!.session)
        assertNull(store.binding)
    }
    @Test fun presetPersonalitySurvivesLogoutAndCannotBeChangedOrDisabled() = runTest {
        val store = MemoryStore()
        val auth = FakeAuth()
        val manager = SessionCoordinator(store, auth) { 1000 }
        val preset = CompanionProfile(style = "playful", name = "小雨")
        manager.login("https://host", "deepseek", "key", "model", preset)
        assertEquals("model", manager.saved()!!.model)
        assertEquals("token-1", manager.token())
        manager.clear()
        assertEquals(preset, store.binding!!.profile)
        try { manager.login("https://host", "deepseek", "key", "model", preset.copy(style = "steady")); fail("Expected fixed personality") } catch (_: IllegalArgumentException) { }
        assertEquals(1, auth.count)
        manager.login("https://host", "deepseek", "replacement-key", "model")
        assertEquals(preset, store.binding!!.profile)
    }
    @Test fun failedProfileAdmissionDoesNotPersistAnIrreversibleChoiceOrKey() = runTest {
        val store = MemoryStore()
        val manager = SessionCoordinator(store, FakeAuth())
        try {
            manager.login("https://host", "deepseek", "key", "model", CompanionProfile()) { throw ApiFailure(409, "已固定") }
            fail("Expected incompatible profile")
        } catch (_: ApiFailure) { }
        assertNull(store.binding)
        assertNull(store.value)
    }
    @Test fun userTrainingCanEvolveButCannotBecomeAPreset() = runTest {
        val store = MemoryStore()
        val manager = SessionCoordinator(store, FakeAuth())
        val custom = CompanionProfile(mode = "custom", instructions = "说话自然一些")
        manager.login("https://host", "deepseek", "key", "model", custom)
        manager.login("https://host", "deepseek", "key", "model", custom.copy(instructions = "叫我小舟"))
        try { manager.login("https://host", "deepseek", "key", "model", CompanionProfile()); fail("Expected fixed choice mode") } catch (_: IllegalArgumentException) { }
        assertEquals(custom.copy(instructions = "叫我小舟"), store.binding!!.profile)
    }
    @Test fun latestUserTrainingSurvivesLogoutAndANewCoordinator() = runTest {
        val store = MemoryStore()
        val manager = SessionCoordinator(store, FakeAuth()) { 1000 }
        val first = CompanionProfile(mode = "custom", instructions = "第一次设定")
        val later = first.copy(instructions = "后来培养的新习惯")
        manager.login("https://host", "deepseek", "key", "model", first)
        manager.updateTraining(later)
        manager.clear()
        val restored = SessionCoordinator(store, FakeAuth()) { 1000 }
        assertEquals(later, restored.binding()!!.profile)
        restored.login("https://host", "deepseek", "key", "model")
        assertEquals(later, restored.binding()!!.profile)
        assertEquals("token-1", restored.token())
    }
    @Test fun legacyVerifiedConnectionCanBeReadMoreThanOnceAndAdoptsProfileOnce() = runTest {
        val store = MemoryStore().apply { value = SavedConnection("https://host", "deepseek", "key", "model", AuthSession("legacy", "deepseek", "model", 5000)) }
        val manager = SessionCoordinator(store, FakeAuth()) { 1000 }
        assertNotNull(manager.saved())
        assertNotNull(manager.saved())
        val original = CompanionProfile(name = "原来的恋人")
        manager.adoptLegacyProfile(original)
        manager.adoptLegacyProfile(original.copy(name = "不能换"))
        assertEquals(original, manager.binding()!!.profile)
        assertEquals("legacy", manager.token())
    }
}
