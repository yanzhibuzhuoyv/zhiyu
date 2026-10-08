package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class UsbCredentialRaceTest {
    private class Store : CredentialStore {
        var value: SavedConnection? = SavedConnection("http://10.0.2.2:8791", "deepseek", "synthetic-key", "fixed-model")
        var beforeCompare: () -> Unit = {}
        var writes = 0
        override fun read() = value
        override fun write(connection: SavedConnection) { value = connection; writes++ }
        override fun clear() { value = null }
        override fun readBinding(): CompanionBinding? = null
        override fun writeBinding(binding: CompanionBinding) = error("Unexpected binding change")
        override fun compareAndWrite(expected: SavedConnection?, updated: SavedConnection): Boolean {
            beforeCompare()
            return if (value != expected) false else { write(updated); true }
        }
    }

    @Test fun usbMigrationCannotBringBackCredentialsClearedBeforeItsCommit() {
        val store = Store().apply { beforeCompare = { clear() } }
        assertNull(UsbDebugCredentialStore(store, true).read())
        assertNull(store.value)
        assertEquals(0, store.writes)
    }

    @Test fun usbMigrationCannotOverwriteANewerBackendConnection() {
        val store = Store()
        val replacement = store.value!!.copy(serverUrl = "https://synthetic.invalid", apiKey = "new-synthetic-key")
        store.beforeCompare = { store.value = replacement }
        assertEquals(replacement, UsbDebugCredentialStore(store, true).read())
        assertEquals(replacement, store.value)
        assertEquals(0, store.writes)
    }
}
