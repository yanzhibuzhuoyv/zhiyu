package com.zhiyu.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Only run on the isolated emulator with localhost:8791 reversed to the demo backend. */
class MediaBackendFlowTest {
    private fun isolated(test: suspend (NetworkChatRepository, VisualRepository, Conversation) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "media_backend_test_$id"
        val store = SecureCredentialStore(context, name, name)
        try {
            val chat = NetworkChatRepository(store, true)
            chat.login("http://127.0.0.1:8791", "demo", "demo-test-$id", "demo")
            val conversation = chat.conversations().single()
            test(chat, VisualRepository(store, true), conversation)
        } finally {
            context.deleteSharedPreferences(name)
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

    @Test fun importedReferenceAndPhotoReplyAreAuthenticatedAndReadable() = isolated { chat, visual, conversation ->
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        val imported = visual.import(conversation.id, bytes)
        assertEquals("import", imported.mode)
        assertNotNull(imported.referenceId)
        assertTrue(visual.download(imported.referenceId!!).isNotEmpty())
        val reply = chat.sendMessage(conversation.id, "发一张你自己的照片")
        val attachment = reply.second.mediaAttachments.single()
        assertEquals("image", attachment.kind)
        assertTrue(visual.download(attachment.id, attachment.sha256).isNotEmpty())
        assertEquals(reply.second.mediaAttachments, chat.history(conversation.id).messages.last().mediaAttachments)
    }

    @Test fun unconfiguredGenerationDoesNotConsumeTheTwoChances() = isolated { _, visual, conversation ->
        assertEquals(2, visual.profile(conversation.id).generation.remaining)
        val failure = try {
            visual.generate(conversation.id, UUID.randomUUID().toString(), "成年原创角色，黑色短发")
            throw AssertionError("Unconfigured generation must fail")
        } catch (error: ApiFailure) { error }
        assertEquals(503, failure.status)
        val quota = visual.profile(conversation.id).generation
        assertFalse(quota.configured)
        assertEquals(2, quota.remaining)
        assertEquals(0, quota.used)
    }
}
