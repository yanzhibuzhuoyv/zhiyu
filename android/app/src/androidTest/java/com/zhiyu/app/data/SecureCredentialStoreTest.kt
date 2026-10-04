package com.zhiyu.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.AuthSession
import com.zhiyu.app.domain.SavedConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore

class SecureCredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "test_zhiyu_credentials"
    private val alias = "test_zhiyu_aes"
    private val store = SecureCredentialStore(context, prefsName, alias)
    private val saved = SavedConnection("https://example.com", "deepseek", "test-secret-api-key", "model", AuthSession("test-secret-token", "deepseek", "model", 9999999999))
    @After fun cleanUp() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
    @Test fun roundTripContainsNoPlaintextAndUsesNewIv() {
        store.write(saved)
        val first = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("encrypted", "")!!
        assertFalse(first.contains(saved.apiKey))
        assertFalse(first.contains(saved.session!!.token))
        assertFalse(first.contains(saved.serverUrl))
        assertEquals(saved, store.read())
        store.write(saved)
        val second = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("encrypted", "")!!
        assertNotEquals(first, second)
        store.clear()
        assertNull(store.read())
    }
    @Test fun corruptedCiphertextCannotSilentlyRestoreCredentials() {
        store.write(saved)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("encrypted", "invalid:ciphertext").commit()
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertNull(store.read())
    }
}
