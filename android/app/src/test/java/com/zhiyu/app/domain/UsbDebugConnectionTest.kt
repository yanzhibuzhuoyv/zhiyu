package com.zhiyu.app.domain

import com.zhiyu.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class UsbDebugConnectionTest {
    @Test fun standardDebugBuildUsesTheUsbBridgeInsteadOfAnEmulatorOnlyAddress() {
        assertEquals("http://127.0.0.1:8791", BuildConfig.DEFAULT_SERVER_URL)
        assertTrue(BuildConfig.USE_USB_BACKEND)
    }

    @Test fun upgradingTheOldLocalConnectionKeepsKeySessionAndFixedPersonality() {
        val store = MemoryStore()
        val original = store.connection!!
        val binding = store.binding
        val usb = UsbDebugCredentialStore(store, true)
        assertEquals(original.copy(serverUrl = "http://127.0.0.1:8791"), usb.read())
        assertEquals(original.apiKey, store.connection!!.apiKey)
        assertSame(original.session, store.connection!!.session)
        assertSame(binding, usb.readBinding())
        assertEquals(1, store.writes)
        usb.read()
        assertEquals(1, store.writes)
    }

    @Test fun releaseAndExplicitNonUsbBuildsNeverRedirectSavedCredentials() {
        val store = MemoryStore()
        assertSame(store.connection, UsbDebugCredentialStore(store, false).read())
        assertEquals(0, store.writes)
    }

    @Test fun customPortsHostsAndPathsStayOnTheirOriginalBackend() {
        val store = MemoryStore()
        for (url in listOf("https://example.com", "http://192.168.1.2:8791",
            "http://10.0.2.2:8790", "http://10.0.2.2:8791/custom", "http://127.0.0.1:8791")) {
            store.connection = store.connection!!.copy(serverUrl = url)
            assertEquals(url, UsbDebugCredentialStore(store, true).read()!!.serverUrl)
        }
        assertEquals(0, store.writes)
    }

    @Test fun failedPersistenceDoesNotPretendThatMigrationSucceeded() {
        val store = MemoryStore().apply { rejectWrite = true }
        val original = store.connection
        assertThrows(IllegalStateException::class.java) { UsbDebugCredentialStore(store, true).read() }
        assertSame(original, store.connection)
        assertEquals("warm_v2", store.binding!!.profile!!.style)
    }

    @Test fun missingCredentialsAndLogoutKeepTheUniqueBinding() {
        val store = MemoryStore()
        val binding = store.binding
        val usb = UsbDebugCredentialStore(store, true)
        usb.clear()
        assertNull(usb.read())
        assertSame(binding, usb.readBinding())
        assertEquals(0, store.writes)
    }

    private class MemoryStore : CredentialStore {
        var connection: SavedConnection? = SavedConnection("http://10.0.2.2:8791", "deepseek", "synthetic-key",
            "test-model", AuthSession("synthetic-session", "deepseek", "test-model", 5000))
        var binding: CompanionBinding? = CompanionBinding("deepseek", "test-model", CompanionProfile(style = "warm_v2"))
        var writes = 0
        var rejectWrite = false
        override fun read() = connection
        override fun write(connection: SavedConnection) {
            check(!rejectWrite) { "Cannot persist" }
            this.connection = connection
            writes++
        }
        override fun clear() { connection = null }
        override fun readBinding() = binding
        override fun writeBinding(binding: CompanionBinding) { this.binding = binding }
    }
}
