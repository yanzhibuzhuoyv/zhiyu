package com.zhiyu.app.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.AuthSession
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.CompanionProfile
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
    @Test fun bindingIsEncryptedAndCredentialClearPreservesIt() {
        val profile = CompanionProfile(mode = "preset", style = "playful", role = "girlfriend", name = "test-private-name", userName = "test-private-user")
        val binding = CompanionBinding("test-private-provider", "test-private-model", profile)
        store.writeBinding(binding)
        store.write(saved)
        val encrypted = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("companion_binding", "")!!
        assertFalse(encrypted.contains(binding.providerId))
        assertFalse(encrypted.contains(binding.model))
        assertFalse(encrypted.contains(profile.name))
        assertFalse(encrypted.contains(profile.userName))
        assertEquals(binding, store.readBinding())
        store.clear()
        assertNull(store.read())
        assertEquals(binding, store.readBinding())
        assertTrue(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).contains("companion_binding"))
    }
    @Test fun confirmedBindingCannotBeOverwrittenWithAnotherCompanyOrModel() {
        val original = CompanionBinding("deepseek", "model-one")
        store.writeBinding(original)
        store.writeBinding(original)
        assertThrows(IllegalStateException::class.java) { store.writeBinding(CompanionBinding("qwen", "model-one")) }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(CompanionBinding("deepseek", "model-two")) }
        assertEquals(original, store.readBinding())
    }
    @Test fun corruptedBindingFailsClosedAndCannotBeReplaced() {
        store.writeBinding(CompanionBinding("deepseek", "model-one"))
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("companion_binding", "invalid:ciphertext").commit()
        assertThrows(IllegalStateException::class.java) { store.readBinding() }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(CompanionBinding("qwen", "replacement")) }
        store.clear()
        assertThrows(IllegalStateException::class.java) { store.readBinding() }
        assertTrue(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).contains("companion_binding"))
    }
    @Test fun credentialCorruptionDoesNotRemoveDurableBinding() {
        val binding = CompanionBinding("deepseek", "model-one")
        store.writeBinding(binding)
        store.write(saved)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString("encrypted", "invalid:ciphertext").commit()
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertEquals(binding, store.readBinding())
        assertNull(store.read())
    }
    @Test fun presetLoverProfileCannotBeChangedOrRemovedAfterBinding() {
        val profile = CompanionProfile(mode = "preset", style = "gentle", role = "partner", name = "知心")
        val binding = CompanionBinding("deepseek", "model-one", profile)
        store.writeBinding(binding)
        assertThrows(IllegalStateException::class.java) { store.writeBinding(binding.copy(profile = profile.copy(style = "playful"))) }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(binding.copy(profile = profile.copy(name = "新名字"))) }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(binding.copy(profile = null)) }
        assertEquals(binding, store.readBinding())
    }
    @Test fun userTrainingCanBePersistedWhileTheAiAndModeRemainFixed() {
        val first = CompanionBinding("deepseek", "model-one")
        store.writeBinding(first)
        val custom = first.copy(profile = CompanionProfile(mode = "custom", instructions = "最初的设定"))
        store.writeBinding(custom)
        val updated = custom.copy(profile = custom.profile!!.copy(instructions = "后来的习惯"))
        store.writeBinding(updated)
        store.clear()
        assertEquals(updated, SecureCredentialStore(context, prefsName, alias).readBinding())
        assertThrows(IllegalStateException::class.java) { store.writeBinding(updated.copy(profile = null)) }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(updated.copy(profile = CompanionProfile())) }
        assertThrows(IllegalStateException::class.java) { store.writeBinding(updated.copy(model = "different")) }
    }
    @Test fun warmPresetRestoresFromEncryptedStorageAndCannotBeReplaced() {
        val profile = CompanionProfile(style = "warm_v1", name = "合成名字", userName = "合成称呼")
        val binding = CompanionBinding("deepseek", "test-model", profile)
        store.writeBinding(binding)
        store.clear()
        val restored = SecureCredentialStore(context, prefsName, alias)
        assertEquals(binding, restored.readBinding())
        assertThrows(IllegalStateException::class.java) {
            restored.writeBinding(binding.copy(profile = profile.copy(style = "gentle")))
        }
        assertThrows(IllegalStateException::class.java) { restored.writeBinding(binding.copy(profile = null)) }
        assertEquals(binding, restored.readBinding())
    }
    @Test fun warmV2PresetRestoresWithoutUnlockingOrChangingVersion() {
        val profile = CompanionProfile(style = "warm_v2", name = "合成名字", userName = "合成称呼")
        val binding = CompanionBinding("deepseek", "test-model", profile)
        store.writeBinding(binding)
        store.clear()
        val restored = SecureCredentialStore(context, prefsName, alias)
        assertEquals(binding, restored.readBinding())
        assertThrows(IllegalStateException::class.java) {
            restored.writeBinding(binding.copy(profile = profile.copy(style = "warm_v1")))
        }
        assertThrows(IllegalStateException::class.java) { restored.writeBinding(binding.copy(profile = null)) }
        assertEquals(binding, restored.readBinding())
    }
}
