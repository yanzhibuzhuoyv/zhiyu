package com.zhiyu.app.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.ContactPauseStore
import com.zhiyu.app.data.LocalChatDatabase
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.data.ProactiveWorkStore
import com.zhiyu.app.data.SecureCredentialStore
import com.zhiyu.app.domain.CompanionBinding
import com.zhiyu.app.domain.ContactPauseCommand
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.Message
import com.zhiyu.app.domain.ProactiveRunRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Recovery reads durable conversation metadata, without a Key or a provider request. */
class ProactiveConversationRecoveryNativeTest {
    private class Fixture : AutoCloseable {
        private val instrument = InstrumentationRegistry.getInstrumentation()
        val context = NativeV4Context(instrument.targetContext)
        val binding = CompanionBinding("synthetic", "conversation-recovery")
        val owner = "companion-" + LocalMemoryCodec.sha("synthetic\u0000conversation-recovery".toByteArray())
        val database = LocalChatDatabase(context)
        val chat = database.ensureConversation(owner, binding.model, null).id
        val state = ProactiveWorkStore(context)
        val now = Instant.now()
        val previous = ProactiveRunRecord(now.minusSeconds(3600), "not_due", now.plusSeconds(12 * 3600))

        init {
            SecureCredentialStore(context).writeBinding(binding)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ProactiveNotifications.createChannel(context)
            state.setEnabled(chat, true)
            state.recordRun(chat, previous)
        }

        fun append(role: String, at: Instant, origin: String = "reply"): Message {
            val message = database.append(owner, chat, role, "合成测试原话", origin = origin)
            database.writableDatabase.execSQL("UPDATE messages SET created_at=? WHERE id=?", arrayOf(at.toString(), message.id))
            return message.copy(createdAt = at.toString())
        }

        override fun close() {
            ProactiveScheduler.cancel(context)
            database.close()
            context.close()
        }
    }

    @Test fun aNewPersistedUserTurnReleasesAnObsoletePolicyDueDuringLifecycleRecovery() = runBlocking {
        Fixture().use { test ->
            val pending = test.state.requestId(test.chat)
            val pauses = ContactPauseStore(test.context)
            val pause = pauses.apply(test.chat, ContactPauseCommand.PAUSE, "synthetic_user_pause")
            test.append("user", test.now.minusSeconds(60))

            ProactiveScheduler.recover(test.context, test.now)

            val recovered = ProactiveWorkStore(test.context).lastRun(test.chat)!!
            assertEquals("An enqueue repair must retain the real last check", test.previous.checkedAt, recovered.checkedAt)
            assertEquals(test.previous.outcome, recovered.outcome)
            assertTrue("A newer user turn must invalidate the old awaiting-reply/policy deadline",
                recovered.nextDue!! <= test.now)
            assertTrue(test.state.enabled(test.chat))
            assertEquals("Never replay a paid attempt under a new identity", pending, test.state.requestId(test.chat))
            assertEquals("A policy recheck cannot clear a conversational pause", pause, pauses.read(test.chat))
        }
    }

    @Test fun recoveryWithoutANewerUserTurnPreservesTheActualPolicyDue() = runBlocking {
        Fixture().use { test ->
            test.append("user", test.previous.checkedAt.minusSeconds(60))
            test.append("assistant", test.now.minusSeconds(30))
            test.append("assistant", test.now.minusSeconds(10), "proactive")
            ProactiveScheduler.recover(test.context, test.now)
            assertEquals(test.previous, test.state.lastRun(test.chat))
        }
    }

    @Test fun anUnansweredUserSendDoesNotPretendTheProviderConnectionWasValidated() = runBlocking {
        Fixture().use { test ->
            val rejected = test.previous.copy(outcome = "needs_connection")
            test.state.recordRun(test.chat, rejected)
            test.append("user", test.now.minusSeconds(60))
            ProactiveScheduler.recover(test.context, test.now)
            assertEquals("A saved user message alone cannot prove provider recovery", rejected, test.state.lastRun(test.chat))
        }
    }

    @Test fun aSameInstantUserTurnIsRecognizedOnceWithoutRepeatedlyResettingARealPolicyDue() = runBlocking {
        Fixture().use { test ->
            test.append("user", test.previous.checkedAt)
            ProactiveScheduler.recover(test.context, test.now)
            assertTrue("An equal timestamp must not lose a new original message", test.state.lastRun(test.chat)!!.nextDue!! <= test.now)
            val realPolicy = test.previous.copy(nextDue = test.now.plusSeconds(4 * 3600))
            test.state.recordRun(test.chat, realPolicy)
            ProactiveScheduler.recover(test.context, test.now)
            assertEquals("The same message identity cannot reset a later real policy decision", realPolicy, test.state.lastRun(test.chat))
        }
    }

    @Test fun ordinaryActivityAndEvenAValidatedConnectionCannotOverrideAnExplicitDisable() = runBlocking {
        Fixture().use { test ->
            val user = test.append("user", test.now.minusSeconds(60))
            test.state.setEnabled(test.chat, false)
            ProactiveScheduler.onConversationActivity(test.context, test.chat, user.id, user.createdAt,
                connectionValidated = true, verifiedAt = test.now).join()
            ProactiveScheduler.recover(test.context, test.now)
            assertFalse(test.state.enabled(test.chat))
            assertEquals(test.previous, test.state.lastRun(test.chat))
        }
    }

    @Test fun aRealProviderSuccessReleasesConnectionBackoffWhileALocalShortcutAndReplayDoNot() = runBlocking {
        Fixture().use { test ->
            val rejected = test.previous.copy(outcome = "needs_connection")
            test.state.recordRun(test.chat, rejected)
            val pending = test.state.requestId(test.chat)
            val user = test.append("user", test.now.minusSeconds(60))
            ProactiveScheduler.onConversationActivity(test.context, test.chat, user.id, user.createdAt).join()
            ProactiveScheduler.recover(test.context, test.now)
            assertEquals("A local shortcut is not provider verification", rejected, test.state.lastRun(test.chat))
            ProactiveScheduler.onConversationActivity(test.context, test.chat, user.id, user.createdAt,
                connectionValidated = true, verifiedAt = test.now).join()
            assertTrue(test.state.lastRun(test.chat)!!.nextDue!! <= Instant.now())
            assertEquals(pending, test.state.requestId(test.chat))
            val realPolicy = test.previous.copy(nextDue = test.now.plusSeconds(4 * 3600))
            test.state.recordRun(test.chat, realPolicy)
            ProactiveScheduler.onConversationActivity(test.context, test.chat, user.id, user.createdAt,
                connectionValidated = true, verifiedAt = test.now).join()
            assertEquals("Replaying a successful reply cannot erase actual personality spacing", realPolicy, test.state.lastRun(test.chat))
        }
    }

    @Test fun aProviderSuccessThatCommittedLateDoesNotOverrideANewerConnectionFailure() = runBlocking {
        Fixture().use { test ->
            val user = test.append("user", test.now.minusSeconds(120))
            val verifiedAt = test.now.minusSeconds(60)
            val newerFailure = test.previous.copy(checkedAt = test.now.minusSeconds(30), outcome = "needs_connection")
            test.state.recordRun(test.chat, newerFailure)
            val pending = test.state.requestId(test.chat)
            ProactiveScheduler.onConversationActivity(test.context, test.chat, user.id, user.createdAt,
                connectionValidated = true, verifiedAt = verifiedAt).join()
            assertEquals("Persisting an older successful reply later does not create a new verification event",
                newerFailure, test.state.lastRun(test.chat))
            assertEquals(pending, test.state.requestId(test.chat))
        }
    }
}
