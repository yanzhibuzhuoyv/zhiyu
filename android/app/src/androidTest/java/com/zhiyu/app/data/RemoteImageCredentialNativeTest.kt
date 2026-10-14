package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.Image2Protocol
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Isolated synthetic token and alias only. Never reads the user's desktop key file. */
class RemoteImageCredentialNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun configurationReturnsAnImmutableMatchedPairWithRedactedDiagnostics() {
        val id = UUID.randomUUID().toString().replace("-", "")
        val name = "zhiyu_remote_image2_snapshot_$id"
        val alias = "zhiyu_remote_image2_snapshot_aes_$id"
        try {
            val store = RemoteImageCredentialStore(context, name, alias)
            assertNull(store.configuration())
            store.write("synthetic-snapshot-one", Image2Protocol.DEFAULT_MODEL)
            val first = requireNotNull(store.configuration())
            assertEquals("synthetic-snapshot-one", first.key)
            assertEquals(Image2Protocol.DEFAULT_MODEL, first.model)
            store.write("synthetic-snapshot-two", "gpt-image-2.5-sunburst")
            val second = requireNotNull(store.configuration())
            assertEquals("synthetic-snapshot-two", second.key)
            assertEquals("gpt-image-2.5-sunburst", second.model)
            assertEquals("synthetic-snapshot-one", first.key)
            assertEquals(Image2Protocol.DEFAULT_MODEL, first.model)
            assertNotEquals(first, second)
            assertEquals(RemoteImageConfiguration(first.key, first.model), first)
            assertEquals(RemoteImageConfiguration(first.key, first.model).hashCode(), first.hashCode())
            assertFalse(first.toString().contains(first.key))
            assertTrue(first.toString().contains("REDACTED"))
            assertEquals(second.key, store.read())
            assertEquals(second.model, store.model())
            store.clear()
            assertNull(store.configuration())
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
    }

    @Test fun independentKeystoreRoundTripKeepsModelAndNeverWritesPlaintext() {
        val id = UUID.randomUUID().toString().replace("-", "")
        val name = "zhiyu_remote_image2_test_$id"
        val alias = "zhiyu_remote_image2_test_aes_$id"
        try {
            val store = RemoteImageCredentialStore(context, name, alias)
            assertNull(store.read())
            store.write("synthetic-image-key", "gpt-image-2.5-sunburst")
            val restored = RemoteImageCredentialStore(context, name, alias)
            assertEquals("synthetic-image-key", restored.read()); assertEquals("gpt-image-2.5-sunburst", restored.model())
            val values = context.getSharedPreferences(name, 0).all.values.joinToString()
            assertFalse(values.contains("synthetic-image-key"))
            restored.clear(); assertNull(restored.read()); assertEquals(Image2Protocol.DEFAULT_MODEL, restored.model())
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
    }
}
