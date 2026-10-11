package com.zhiyu.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.SavedConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID

/** Synthetic pictures and UUID-owned preferences only; no cloud or real credentials. */
class AgnesImageNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "zhiyu_agnes_test_${UUID.randomUUID().toString().replace("-", "")}"
    private val imagePrefs = "${namespace}_image"
    private val imageAlias = "${namespace}_image_aes"
    private val chatPrefs = "${namespace}_chat"
    private val chatAlias = "${namespace}_chat_aes"

    @After fun cleanOwnedStorage() {
        listOf(imagePrefs, chatPrefs).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            listOf(imageAlias, chatAlias).forEach { if (containsAlias(it)) deleteEntry(it) }
        }
    }

    @Test fun imageKeyRoundTripsWithFreshIvAndCannotChangeMainRelationship() {
        val image = AgnesCredentialStore(context, imagePrefs, imageAlias)
        val chat = SecureCredentialStore(context, chatPrefs, chatAlias)
        val binding = CompanionBinding("deepseek", "synthetic-model")
        val saved = SavedConnection("https://local.zhiyu.invalid", "deepseek", "synthetic-chat-key", "synthetic-model", null)
        chat.writeBinding(binding); chat.write(saved)
        image.write("synthetic-image-key")
        val prefs = context.getSharedPreferences(imagePrefs, Context.MODE_PRIVATE)
        val first = prefs.getString("encrypted", null)!!
        assertFalse(first.contains("synthetic-image-key"))
        assertEquals("synthetic-image-key", AgnesCredentialStore(context, imagePrefs, imageAlias).read())
        image.write("synthetic-image-key")
        assertNotEquals(first, prefs.getString("encrypted", null))
        image.clear()
        assertNull(image.read())
        assertEquals(saved, chat.read())
        assertEquals(binding, chat.readBinding())
    }

    @Test fun corruptImageCiphertextFailsClosedWithoutSilentlyReplacingIt() {
        val image = AgnesCredentialStore(context, imagePrefs, imageAlias)
        image.write("synthetic-image-key")
        val prefs = context.getSharedPreferences(imagePrefs, Context.MODE_PRIVATE)
        prefs.edit().putString("encrypted", "invalid:ciphertext").commit()
        assertThrows(IllegalStateException::class.java) { image.read() }
        assertEquals("invalid:ciphertext", prefs.getString("encrypted", null))
        image.clear()
        assertNull(image.read())
    }

    @Test fun realBitmapBoundsAcceptSmallPngAndJpegButRejectTruncatedAndWrongMime() {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        try {
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            AndroidAgnesImageBounds.validate(png, "image/png")
            AndroidAgnesImageBounds.validate(jpeg, "image/jpeg")
            assertThrows(IllegalArgumentException::class.java) { AndroidAgnesImageBounds.validate(png.copyOf(8), "image/png") }
            assertThrows(IllegalArgumentException::class.java) { AndroidAgnesImageBounds.validate(png, "image/jpeg") }
        } finally { bitmap.recycle() }
    }
}
