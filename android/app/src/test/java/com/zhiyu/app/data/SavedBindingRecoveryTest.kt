package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SavedBindingRecoveryTest {
    @Test fun readingALegacyBindingWorksWithoutAnyReachableServerAndCannotEraseItsProfile() = runTest {
        val connection = SavedConnection("invalid-offline-server", "deepseek", "synthetic-key", "fixed-model",
            AuthSession("synthetic-token", "deepseek", "fixed-model", Long.MAX_VALUE))
        val legacy = CompanionBinding("deepseek", "fixed-model", profileConfigured = false)
        val store = OfflineCredentialStore(connection, legacy)
        val repository = NetworkChatRepository(store, false)
        assertEquals(legacy, repository.savedBinding())
        assertEquals(connection, repository.savedConnection())
        assertEquals(legacy, store.binding)
        assertEquals(0, store.writes)
    }

    @Test fun readingAnOlderVerifiedConnectionAdoptsItsAiLocallyBeforeTheServerIsAvailable() = runTest {
        val connection = SavedConnection("invalid-offline-server", "deepseek", "synthetic-key", "fixed-model",
            AuthSession("synthetic-token", "deepseek", "fixed-model", Long.MAX_VALUE))
        val store = OfflineCredentialStore(connection, null)
        val repository = NetworkChatRepository(store, false)
        assertEquals(CompanionBinding("deepseek", "fixed-model", profileConfigured = false), repository.savedBinding())
        assertEquals(connection, repository.savedConnection())
        assertEquals(1, store.writes)
    }

    @Test fun aFixedBindingWithoutAStoredKeyRemainsAvailableOffline() = runTest {
        val binding = CompanionBinding("deepseek", "fixed-model", CompanionProfile(name = "原来的恋人"))
        val store = OfflineCredentialStore(null, binding)
        val repository = NetworkChatRepository(store, false)
        assertEquals(binding, repository.savedBinding())
        assertNull(repository.savedConnection())
        assertEquals(0, store.writes)
    }
}

private class OfflineCredentialStore(var connection: SavedConnection?, var binding: CompanionBinding?) : CredentialStore {
    var writes = 0
    override fun read() = connection
    override fun readBinding() = binding
    override fun write(connection: SavedConnection) { writes++; this.connection = connection }
    override fun writeBinding(binding: CompanionBinding) { writes++; this.binding = binding }
    override fun clear(): Unit = error("Recovery must not clear credentials")
}
