package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class PreviewEndpointCredentialStoreTest {
    private val endpoint = "https://192.0.2.21:8792"

    @Test fun anExistingPhoneUsbConnectionMovesToTheConfiguredPreviewWithoutChangingTheRelationshipOrKey() {
        assertMigration("http://127.0.0.1:8791")
    }

    @Test fun anExistingEmulatorUsbConnectionMovesToTheConfiguredPreviewWithoutChangingTheRelationshipOrKey() {
        assertMigration("http://10.0.2.2:8791")
    }

    @Test fun aCustomBackendIsNeverRedirectedOrSentToThePreviewEndpoint() {
        listOf("https://user.example", "http://192.0.2.40:8791", "http://127.0.0.1:8900").forEach { url ->
            val store = Store(connection(url))
            val original = store.value
            assertEquals(original, PreviewEndpointCredentialStore(store, true, endpoint).read())
            assertEquals(0, store.writes)
        }
    }

    @Test fun aConnectionAlreadyUsingThePreviewKeepsItsNewSession() {
        val store = Store(connection(endpoint))
        val original = store.value
        val wrapper = PreviewEndpointCredentialStore(store, true, endpoint)
        assertEquals(original, wrapper.read())
        assertEquals(original, wrapper.read())
        assertEquals(0, store.writes)
    }

    @Test fun disabledPreviewMigrationCannotRedirectAReleaseOrRegularUsbConnection() {
        val store = Store(connection("http://127.0.0.1:8791"))
        val original = store.value
        assertEquals(original, PreviewEndpointCredentialStore(store, false, endpoint).read())
        assertEquals(original, PreviewEndpointCredentialStore(store, false, "").read())
        assertEquals(original, PreviewEndpointCredentialStore(store, false, "http://127.0.0.1:8791").read())
        assertEquals(0, store.writes)
    }

    @Test fun enabledPreviewRequiresAHttpsEndpointWithoutCredentialQueryOrFragment() {
        listOf("", "http://192.0.2.21:8792", "https://user:password@example.test", "https://example.test?key=value",
            "https://example.test#fragment", "https://example.test:70000").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PreviewEndpointCredentialStore(Store(null), true, invalid) }
        }
    }

    @Test fun clearingCredentialsDuringMigrationCannotBringBackTheOldKey() {
        val store = Store(connection("http://127.0.0.1:8791"))
        val binding = store.binding
        store.beforeCompare = { store.clear() }
        assertNull(PreviewEndpointCredentialStore(store, true, endpoint).read())
        assertNull(store.value)
        assertEquals(binding, store.binding)
        assertEquals(0, store.writes)
    }

    @Test fun anotherRepositorysNewConnectionCannotBeOverwrittenByMigration() {
        val store = Store(connection("http://10.0.2.2:8791"))
        val replacement = connection("https://user.example").copy(apiKey = "new-synthetic-key")
        store.beforeCompare = { store.value = replacement }
        assertEquals(replacement, PreviewEndpointCredentialStore(store, true, endpoint).read())
        assertEquals(replacement, store.value)
        assertEquals(0, store.writes)
    }

    @Test fun aMissingKeyRemainsMissingAndItsPermanentBindingIsStillReadable() {
        val store = Store(null)
        val wrapper = PreviewEndpointCredentialStore(store, true, endpoint)
        assertNull(wrapper.read())
        assertEquals(store.binding, wrapper.readBinding())
        assertEquals(0, store.writes)
    }

    @Test fun compareAndWriteUsesTheUnderlyingAtomicSnapshotAndCannotOverwriteANewerKey() {
        val store = Store(connection(endpoint))
        val wrapper = PreviewEndpointCredentialStore(store, true, endpoint)
        val expected = wrapper.read()!!
        val replacement = expected.copy(apiKey = "new-synthetic-key")
        store.value = replacement
        assertFalse(wrapper.compareAndWrite(expected, expected.copy(session = null)))
        assertEquals(replacement, store.value)
        assertEquals(0, store.writes)
    }

    @Test fun ordinaryWritesAndLogoutContinueToUseTheUnderlyingSecureStore() {
        val store = Store(null)
        val wrapper = PreviewEndpointCredentialStore(store, true, endpoint)
        val newer = connection(endpoint)
        wrapper.write(newer)
        assertEquals(newer, wrapper.read())
        val updatedBinding = store.binding.copy(profile = CompanionProfile(mode = "custom", instructions = "说话自然些"))
        wrapper.writeBinding(updatedBinding)
        assertEquals(updatedBinding, wrapper.readBinding())
        wrapper.clear()
        assertNull(wrapper.read())
        assertEquals(updatedBinding, wrapper.readBinding())
    }

    private fun assertMigration(originalUrl: String) {
        val original = connection(originalUrl)
        val store = Store(original)
        val binding = store.binding
        val wrapper = PreviewEndpointCredentialStore(store, true, " $endpoint/ ")
        val migrated = wrapper.read()
        assertEquals(original.copy(serverUrl = endpoint, session = null), migrated)
        assertEquals(migrated, store.value)
        assertEquals(binding, wrapper.readBinding())
        assertEquals(0, store.bindingWrites)
        assertEquals(1, store.writes)
        assertEquals(migrated, wrapper.read())
        assertEquals(1, store.writes)
    }

    private fun connection(url: String) = SavedConnection(url, "deepseek", "synthetic-key", "fixed-model",
        AuthSession("old-synthetic-token", "deepseek", "fixed-model", 5000))

    private class Store(var value: SavedConnection?) : CredentialStore {
        var binding = CompanionBinding("deepseek", "fixed-model", CompanionProfile(style = "warm_v2"))
        var beforeCompare: () -> Unit = {}
        var writes = 0
        var bindingWrites = 0
        override fun read() = value
        override fun write(connection: SavedConnection) { value = connection; writes++ }
        override fun clear() { value = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding; bindingWrites++ }
        override fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection): Boolean {
            beforeCompare()
            return if (value != expected) false else { write(updated); true }
        }
    }
}
