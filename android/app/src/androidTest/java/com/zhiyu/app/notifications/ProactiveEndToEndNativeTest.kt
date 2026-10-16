package com.zhiyu.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import com.zhiyu.app.ui.ProactiveController
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Real service + repository + SQLite + NotificationManager; only the remote transport is synthetic. */
class ProactiveEndToEndNativeTest {
    private class Store : CredentialStore {
        val binding = CompanionBinding("deepseek", "deepseek-flash", CompanionProfile(style = "anxious", name = "合成恋人"))
        override fun read() = SavedConnection(LocalChatRepository.LOCAL_URL, "deepseek", "synthetic-contact-key", binding.model)
        override fun readBinding() = binding
        override fun write(connection: SavedConnection) = error("No credential changes")
        override fun writeBinding(binding: CompanionBinding) = error("No binding changes")
        override fun clear() = error("No credential changes")
    }
    private class Transport : DirectProviderTransport {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun exchange(endpoint: String, key: String, json: String): String {
            assertEquals("synthetic-contact-key", key)
            assertTrue(JSONObject(json).getJSONArray("messages").length() > 1)
            calls++; started.complete(Unit); gate?.await()
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", "我刚泡了杯茶，忽然想跟你说句话。")))).toString()
        }
    }
    private class Fixture : AutoCloseable {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = CareTestContext(instrumentation.targetContext)
        val databaseName = "contact_pipeline_${UUID.randomUUID()}.sqlite3"
        val archive = LocalChatDatabase(context, databaseName)
        val credentials = Store()
        val transport = Transport()
        val now = Instant.now()
        val owner = "companion-" + LocalMemoryCodec.sha("deepseek\u0000deepseek-flash".toByteArray(Charsets.UTF_8))
        val chat = archive.ensureConversation(owner, credentials.binding.model, credentials.binding.profile)
        val repository = LocalChatRepository(context, credentials, DirectProviderClient(transport), database = archive)
        val service = LocalProactiveService(context, credentials, RepositoryProactiveGateway(repository), now = { now })
        val state = ProactiveWorkStore(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val priorVisible = ProactiveForeground.isVisible()
        init {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            assertTrue(ProactiveNotifications.allowed(context))
            val user = archive.append(owner, chat.id, "user", "今天有点累，准备去歇一会儿。")
            archive.writableDatabase.execSQL("UPDATE messages SET created_at=? WHERE id=?",
                arrayOf(now.minusSeconds(3600).toString(), user.id))
        }
        suspend fun enableThroughController() {
            val controller = ProactiveController(context, chat.id, scope, service)
            controller.restore().join()
            assertTrue(controller.ready)
            assertFalse(controller.preferences.enabled)
            controller.toggle(true).join()
            assertTrue(controller.preferences.enabled)
            assertTrue(state.enabled(chat.id))
            assertTrue(service.preferences(chat.id).enabled)
        }
        fun runner() = ProactiveWorkRunner(context, credentials, service) { now }
        fun notifications() = manager.activeNotifications.filter { note ->
            archive.history(owner, chat.id).messages.any { it.id == note.tag && it.origin == "proactive" }
        }
        override fun close() {
            scope.cancel()
            archive.history(owner, chat.id).messages.filter { it.origin == "proactive" }.forEach { manager.cancel(it.id, 1) }
            ProactiveScheduler.cancel(context)
            archive.close(); context.deleteDatabase(databaseName); context.cleanup()
            ProactiveForeground.setVisible(priorVisible)
        }
    }

    @Test fun grantedContactReachesDueGenerationArchiveAndOneRealBackgroundNotification() = runBlocking {
        Fixture().use { test ->
            test.enableThroughController()
            test.state.recordRun(test.chat.id, ProactiveRunRecord(test.now.minusSeconds(60), "not_due", test.now.minusSeconds(1)))
            ProactiveForeground.setVisible(false)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat.id))
            assertEquals(1, test.transport.calls)
            val message = test.archive.history(test.owner, test.chat.id).messages.single { it.origin == "proactive" }
            assertEquals("assistant", message.role)
            assertEquals("我刚泡了杯茶，忽然想跟你说句话。", message.content)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (test.notifications().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            val notification = test.notifications().single().notification
            assertEquals(message.content, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals("合成恋人", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("sent", test.state.lastRun(test.chat.id)?.outcome)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat.id))
            assertEquals(1, test.transport.calls)
            assertEquals(1, test.notifications().size)
        }
    }

    @Test fun foregroundSkipsGenerationWithoutInventingAContactOrNotification() = runBlocking {
        Fixture().use { test ->
            test.enableThroughController()
            ProactiveForeground.setVisible(true)
            assertEquals(ProactiveWorkOutcome.COMPLETE, test.runner().run(test.chat.id))
            assertEquals(0, test.transport.calls)
            assertTrue(test.notifications().isEmpty())
            assertTrue(test.archive.history(test.owner, test.chat.id).messages.none { it.origin == "proactive" })
            assertEquals("foreground", test.state.lastRun(test.chat.id)?.outcome)
        }
    }

    @Test fun aGrantedContactIsPersistedEvenIfItsScreenClosesDuringLocalPreferenceSaving() = runBlocking {
        Fixture().use { test ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed = object : ProactiveService by test.service {
                override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences {
                    entered.complete(Unit); release.await()
                    return test.service.savePreferences(id, preferences)
                }
            }
            val controller = ProactiveController(test.context, test.chat.id, test.scope, delayed)
            controller.restore().join()
            val job = controller.toggle(true)
            try {
                withTimeout(5000) { entered.await() }
                test.scope.cancel()
                release.complete(Unit)
                job.join()
                val deadline = SystemClock.elapsedRealtime() + 3000
                while (!test.state.enabled(test.chat.id) && SystemClock.elapsedRealtime() < deadline) delay(20)
                assertTrue("Granted notification should not be lost with its Activity scope", test.state.enabled(test.chat.id))
                assertTrue(test.service.preferences(test.chat.id).enabled)
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }
}
