package com.zhiyu.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionCredentialRaceTest {
    private class Store : CredentialStore {
        var value: SavedConnection? = SavedConnection("https://synthetic.invalid", "deepseek", "synthetic-key", "fixed-model",
            AuthSession("expired", "deepseek", "fixed-model", 999))
        var binding: CompanionBinding? = CompanionBinding("deepseek", "fixed-model", CompanionProfile(style = "warm_v2"))
        override fun read() = value
        override fun write(connection: SavedConnection) { value = connection }
        override fun clear() { value = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }

    @Test fun clearingCredentialsWhileAuthIsSuspendedCannotRestoreTheOldKey() = runTest {
        val store = Store()
        val binding = store.binding
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<AuthSession>()
        val coordinator = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession { started.complete(Unit); return response.await() }
        }) { 1000 }
        val refresh = async { runCatching { coordinator.token() } }
        started.await()
        SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession = error("Unexpected validation")
        }).clear()
        response.complete(AuthSession("stale-token", "deepseek", "fixed-model", 5000))
        val failure = refresh.await().exceptionOrNull()
        assertTrue(failure is ApiFailure)
        assertEquals(401, (failure as ApiFailure).status)
        assertNull(store.value)
        assertEquals(binding, store.binding)
    }

    @Test fun anotherRepositorysNewSessionCannotBeOverwrittenByTheOlderRefresh() = runTest {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<AuthSession>()
        val coordinator = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession { started.complete(Unit); return response.await() }
        }) { 1000 }
        val refresh = async { coordinator.token() }
        started.await()
        val latest = store.value!!.copy(session = AuthSession("another-repo-token", "deepseek", "fixed-model", 5000))
        store.write(latest)
        response.complete(AuthSession("older-refresh-token", "deepseek", "fixed-model", 5000))
        assertEquals("another-repo-token", refresh.await())
        assertEquals(latest, store.value)
    }

    @Test fun replacingTheKeyDuringRefreshCannotRestoreThePreviousKey() = runTest {
        val store = Store()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<AuthSession>()
        val coordinator = SessionCoordinator(store, object : AuthService {
            override suspend fun authenticate(connection: SavedConnection): AuthSession { started.complete(Unit); return response.await() }
        }) { 1000 }
        val refresh = async { runCatching { coordinator.token() } }
        started.await()
        val replacement = store.value!!.copy(apiKey = "new-synthetic-key", session = AuthSession("new-owner-token", "deepseek", "fixed-model", 5000))
        store.write(replacement)
        response.complete(AuthSession("stale-token", "deepseek", "fixed-model", 5000))
        assertEquals(401, (refresh.await().exceptionOrNull() as? ApiFailure)?.status)
        assertEquals(replacement, store.value)
    }
}
