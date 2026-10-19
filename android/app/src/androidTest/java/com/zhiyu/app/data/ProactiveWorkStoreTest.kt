package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.time.Instant
import com.zhiyu.app.domain.ProactiveRunRecord

/** Separate test preferences only; no API Key, cloud call, worker execution or permission mutation. */
class ProactiveWorkStoreTest {
    private fun store(test: (ProactiveWorkStore, () -> ProactiveWorkStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "proactive_test_${UUID.randomUUID()}"
        try { test(ProactiveWorkStore(context, name)) { ProactiveWorkStore(context, name) } }
        finally { context.deleteSharedPreferences(name) }
    }

    @Test fun contactStartsDisabledAndItsOptInPersists() = store { state, recreate ->
        assertFalse(state.enabled("chat"))
        state.setEnabled("chat", true)
        assertTrue(recreate().enabled("chat"))
        state.setEnabled("chat", false)
        assertFalse(recreate().enabled("chat"))
    }

    @Test fun requestIdSurvivesRecreationUntilTheExactRequestCompletes() = store { state, recreate ->
        val id = state.requestId("chat")
        UUID.fromString(id)
        assertEquals(id, recreate().requestId("chat"))
        recreate().complete("chat", UUID.randomUUID().toString())
        assertEquals(id, recreate().requestId("chat"))
        recreate().complete("chat", id)
        assertNotEquals(id, recreate().requestId("chat"))
    }

    @Test fun theSameMessageCannotBeNotifiedAgainAfterRecreation() = store { state, recreate ->
        assertTrue(state.claimMessage("message-1"))
        assertFalse(recreate().claimMessage("message-1"))
        assertTrue(recreate().claimMessage("message-2"))
    }

    @Test fun nextCheckAndSafeOutcomeSurviveProcessStyleRecreation() = store { state, recreate ->
        assertNull(state.lastRun("chat"))
        val record = ProactiveRunRecord(Instant.parse("2026-10-12T12:00:00Z"), "not_due", Instant.parse("2026-10-12T13:30:00Z"))
        state.recordRun("chat", record)
        assertEquals(record, recreate().lastRun("chat"))
        assertNull(recreate().lastRun("other_chat"))
        val denied = record.copy(outcome = "permission", nextDue = null)
        recreate().recordRun("chat", denied)
        assertEquals(denied, recreate().lastRun("chat"))
    }

    @Test fun replayingConnectionValidationDoesNotPushAnAlreadyReleasedDeadlineForward() = store { state, recreate ->
        val verifiedAt = Instant.parse("2026-10-19T04:00:00Z")
        state.setEnabled("chat", true)
        val record = ProactiveRunRecord(verifiedAt.minusSeconds(60), "needs_connection", verifiedAt.plusSeconds(6 * 3600))
        state.recordRun("chat", record)
        val pending = state.requestId("chat")
        assertTrue(state.releaseConnectionBackoff("chat", verifiedAt))
        assertFalse("Replaying verification is not a new policy deadline",
            recreate().releaseConnectionBackoff("chat", verifiedAt.plusSeconds(60)))
        assertEquals(record.copy(nextDue = verifiedAt), recreate().lastRun("chat"))
        assertEquals(pending, recreate().requestId("chat"))
    }

    @Test fun aLateValidationCannotReleaseAProviderFailureCheckedAfterTheValidationEvent() = store { state, recreate ->
        val verifiedAt = Instant.parse("2026-10-19T04:00:00Z")
        state.setEnabled("chat", true)
        val newerFailure = ProactiveRunRecord(verifiedAt.plusSeconds(30), "needs_connection", verifiedAt.plusSeconds(6 * 3600))
        state.recordRun("chat", newerFailure)
        assertFalse("A delayed older success cannot overrule a newer provider rejection",
            recreate().releaseConnectionBackoff("chat", verifiedAt))
        assertEquals(newerFailure, recreate().lastRun("chat"))
    }
}
