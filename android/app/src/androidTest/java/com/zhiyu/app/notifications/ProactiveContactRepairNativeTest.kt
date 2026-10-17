package com.zhiyu.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProactiveContactRepairNativeTest {
    @Test fun anExplicitRepairPersistsTheSingleGrantAfterItsScreenClosesAndKeepsContactPaused() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33 && base.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ProactiveNotifications.createChannel(base)
        NativeV4Context(base).use { context ->
            val binding = CompanionBinding("synthetic", "repair-model")
            SecureCredentialStore(context).writeBinding(binding)
            val owner = "companion-" + LocalMemoryCodec.sha("synthetic\u0000repair-model".toByteArray(Charsets.UTF_8))
            val chat = LocalChatDatabase(context).use { it.ensureConversation(owner, binding.model, null) }
            val credentials = object : CredentialStore {
                override fun read(): SavedConnection? = error("No Key")
                override fun readBinding(): CompanionBinding? = error("No binding needed")
                override fun write(connection: SavedConnection) = error("No mutation")
                override fun writeBinding(binding: CompanionBinding) = error("No mutation")
                override fun clear() = error("No mutation")
            }
            val gateway = object : LocalProactiveGateway {
                override suspend fun conversations() = listOf(chat)
                override suspend fun result(id: String, requestId: String): Message? = error("No generation")
                override suspend fun history(id: String): List<Message> = error("No generation")
                override suspend fun send(id: String, requestId: String, event: LocalNightEvent?): Message = error("No generation")
            }
            val real = LocalProactiveService(context, credentials, gateway)
            check(context.getSharedPreferences("proactive_delivery", 0).edit().putBoolean("enabled_${chat.id}", false).commit())
            val legacy = JSONObject().put("enabled", true).put("start", 22).put("end", 8).put("zone", "Asia/Shanghai")
            check(context.getSharedPreferences("local_proactive", 0).edit().putString(chat.id, legacy.toString()).commit())
            val state = ProactiveWorkStore(context)
            assertFalse(state.enabled(chat.id))
            assertEquals("legacy_conflict", LocalProactivePreferencesStore(context).migrationIssue(chat.id))
            val old = ProactiveRunRecord(Instant.now().minusSeconds(300), "not_due", Instant.now().plusSeconds(86400))
            state.recordRun(chat.id, old)
            val pending = state.requestId(chat.id)
            val pauses = ContactPauseStore(context)
            val expected = pauses.apply(chat.id, ContactPauseCommand.PAUSE, "synthetic_user_stop")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delayed = object : ProactiveService by real {
                override suspend fun savePreferences(id: String, preferences: ProactivePreferences): ProactivePreferences {
                    assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    entered.complete(Unit); release.await(); return real.savePreferences(id, preferences)
                }
            }
            val screen = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val repair = ProactiveScheduler.restoreContact(context, delayed)
            val presentation = screen.launch { repair.await() }
            try {
                withTimeout(5000) { entered.await() }
                screen.cancel(); presentation.join(); release.complete(Unit)
                withTimeout(5000) { repair.await() }
                assertTrue(ProactiveWorkStore(context).enabled(chat.id))
                assertTrue(real.preferences(chat.id).enabled)
                assertEquals(expected, pauses.read(chat.id))
                assertNull(LocalProactivePreferencesStore(context).migrationIssue(chat.id))
                val updated = state.lastRun(chat.id)!!
                assertEquals(old.checkedAt, updated.checkedAt)
                assertEquals(old.outcome, updated.outcome)
                assertTrue("Release only the obsolete disabled deferral", updated.nextDue!! <= Instant.now())
                assertEquals(pending, state.requestId(chat.id))
                val resume = WorkManager.getInstance(context).getWorkInfosForUniqueWork("zhiyu_companion_resume")
                    .get(5, TimeUnit.SECONDS).last { !it.state.isFinished }
                assertTrue(resume.nextScheduleTimeMillis <= Instant.now().plusSeconds(35).toEpochMilli())
                val actualDue = old.copy(nextDue = Instant.now().plusSeconds(4 * 3600))
                state.recordRun(chat.id, actualDue)
                ContactPreferenceWriter.submit(context, chat.id, real.preferences(chat.id), real).await()
                assertEquals("True-to-true saves keep actual personality spacing", actualDue, state.lastRun(chat.id))
                assertEquals(pending, state.requestId(chat.id))
            } finally {
                release.complete(Unit); screen.cancel(); repair.cancelAndJoin()
                ProactiveScheduler.cancel(context)
            }
        }
    }
}
