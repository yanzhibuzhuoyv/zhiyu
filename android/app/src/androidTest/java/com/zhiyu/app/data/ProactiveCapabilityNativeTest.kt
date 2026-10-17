package com.zhiyu.app.data

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveNotifications
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The repository must read current local gates for every real provider payload. */
class ProactiveCapabilityNativeTest {
    @Test fun permissionOptInAndPauseChangesReachTheNextRequestInsteadOfOldHistory(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.repo.conversations().single()
            f.repo.sendMessage(chat.id, "你能主动找我吗")
            assertTrue(f.system().contains("proactive_contact_state=user_disabled"))
            ProactiveWorkStore(f.context).setEnabled(chat.id, true)
            f.repo.sendMessage(chat.id, "之前说不能，现在呢")
            assertTrue(f.system().contains("proactive_contact_state=ready"))
            ContactPauseStore(f.context).apply(chat.id, ContactPauseCommand.PAUSE, "new-pause")
            f.repo.sendMessage(chat.id, "先这样")
            assertTrue(f.system().contains("proactive_contact_state=paused"))
            assertEquals(6, f.db.history(f.owner, chat.id).messages.size)
        }
    }

    @Test fun revokedNotificationAccessIsNotMisreportedAsReady(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.repo.conversations().single()
            ProactiveWorkStore(f.context).setEnabled(chat.id, true)
            f.blockNotifications = true
            f.repo.sendMessage(chat.id, "能主动找我吗")
            assertTrue(f.system().contains("proactive_contact_state=notifications_blocked"))
            f.blockNotifications = false
            f.repo.sendMessage(chat.id, "现在呢")
            assertTrue(f.system().contains("proactive_contact_state=ready"))
        }
    }

    @Test fun unreadablePauseStateProducesUnknownRatherThanInventingPermission(): Unit = runBlocking {
        Fixture().use { f ->
            val chat = f.repo.conversations().single()
            ProactiveWorkStore(f.context).setEnabled(chat.id, true)
            check(f.context.getSharedPreferences("contact_pause_v1", 0).edit().putString(chat.id, "broken").commit())
            assertNull(ConversationRuntimeReader(f.context).proactive(chat.id))
            f.repo.sendMessage(chat.id, "我只是问问你能不能主动找我")
            assertTrue(f.system().contains("proactive_contact_state=unknown"))
        }
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val base = instrumentation.targetContext
        private val isolated = NativeV4Context(base)
        var blockNotifications = false
        val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext() = this
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS && blockNotifications)
                    PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val db = LocalChatDatabase(context, "capability.sqlite3")
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray())
        private val binding = CompanionBinding("deepseek", "deepseek-flash", CompanionProfile(style = "warm_v2"))
        private val store = object : CredentialStore {
            override fun read() = SavedConnection(LocalChatRepository.LOCAL_URL, binding.providerId, "synthetic-capability-key", binding.model)
            override fun readBinding() = binding
            override fun write(connection: SavedConnection) = error("unused")
            override fun writeBinding(binding: CompanionBinding) = error("unused")
            override fun clear() = error("unused")
        }
        private var lastSystem: String? = null
        val repo = LocalChatRepository(context, store, DirectProviderClient { _, key, body ->
            assertEquals("synthetic-capability-key", key)
            val rows = JSONObject(body).getJSONArray("messages")
            lastSystem = (0 until rows.length()).map { rows.getJSONObject(it) }
                .single { it.getString("role") == "system" }.getString("content")
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"嗯，我听着。"}}]}"""
        }, database = db, semanticEncoder = object : LocalSemanticEncoder {
            override val modelIdentity = "capability-none"; override val dimensions = 2
            override fun isAvailable() = false
            override fun documentChunkCount(text: String): Int = error("unused")
            override suspend fun encodeDocument(text: String): FloatArray = error("unused")
            override suspend fun encodeQuery(query: String): FloatArray = error("unused")
            override suspend fun encodeDocumentChunks(text: String, startChunk: Int, maxChunks: Int): List<SemanticChunk> = error("unused")
            override suspend fun encodeQueryChunks(query: String): List<SemanticChunk> = error("unused")
        }, daily = null)
        init {
            if (Build.VERSION.SDK_INT >= 33 && base.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(base)
            assertTrue(ProactiveNotifications.allowed(context))
            db.ensureConversation(owner, binding.model, binding.profile)
        }
        fun system() = checkNotNull(lastSystem)
        override fun close() {
            db.close(); isolated.close()
            val folder = isolated.filesDir.canonicalFile
            check(folder.parentFile == base.filesDir.canonicalFile && folder.name.startsWith("v4_"))
            check(folder.deleteRecursively())
        }
    }
}
