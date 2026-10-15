package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated synthetic originals and controlled source pointers; no model, Key or microphone. */
class CallInterruptedMemoryOriginNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "call_origin_${UUID.randomUUID()}.sqlite3"
    private var archive = LocalChatDatabase(context, name)
    private val owner = "synthetic-call-origin"
    private val chat = archive.ensureConversation(owner, "synthetic-model", null)
    @After fun cleanup() { archive.close(); context.deleteDatabase(name) }

    @Test fun semanticMaterializationPreservesTheInterruptedOriginAndUntouchedOriginal() {
        val source = archive.append(owner, chat.id, "assistant", "周六陪你读书🐈，", origin = "call_interrupted")
        val result = archive.semanticRecall(owner, chat.id, hit(source))
        assertEquals(source.content, result.text)
        assertEquals(source.id, result.messageId)
        assertEquals(source.createdAt, result.createdAt)
        assertEquals("call_interrupted", result.origin)
        assertEquals(source, archive.history(owner, chat.id).messages.single())
    }

    @Test fun precisionMaterializationKeepsEachIndependentOriginWithoutChangingRanking() {
        val complete = archive.append(owner, chat.id, "assistant", "周六陪你读书。")
        val prefix = archive.append(owner, chat.id, "assistant", "周六陪你读书，", origin = "call_interrupted")
        val results = archive.recall(owner, chat.id, "周六读书")
        assertEquals(listOf(prefix.id, complete.id), results.map { it.messageId })
        assertEquals(listOf("call_interrupted", "reply"), results.map { it.origin })
        assertEquals(listOf(prefix.content, complete.content), results.map { it.text })
        assertEquals(listOf(complete, prefix), archive.history(owner, chat.id).messages)
    }

    @Test fun coldPrecisionAndRecentContextKeepOriginAcrossDatabaseReopen() {
        val source = archive.append(owner, chat.id, "assistant", "周六陪你读书，", origin = "call_interrupted")
        archive.changeMemory(owner, chat.id, source.id, MemoryChange(0, indexState = "cold"))
        archive.close(); archive = LocalChatDatabase(context, name)
        val recalled = archive.recall(owner, chat.id, "周六读书", state = "cold").single()
        assertEquals("call_interrupted", recalled.origin)
        assertEquals(source.content, recalled.text)
        assertEquals("call_interrupted", archive.promptContext(owner, chat.id).messages.single().origin)
        assertEquals(source, archive.history(owner, chat.id).messages.single())
    }

    @Test fun correctionClosureCannotUpgradeAnInterruptedSuccessorToAnOrdinaryReply() {
        val old = archive.append(owner, chat.id, "assistant", "我答应周日陪你读书。")
        val prefix = archive.append(owner, chat.id, "assistant", "更正，我取消了周日陪你读书的约定，", origin = "call_interrupted")
        val recognition = LocalMemoryRecognitionSqlStore(archive)
        val identity = LocalMemoryRecognitionIdentity(owner, chat.id, "deepseek", chat.model)
        recognition.enqueue(identity)
        repeat(2) {
            val job = checkNotNull(recognition.claim(identity))
            val source = checkNotNull(recognition.original(job))
            val window = LocalMemoryRecognitionRules.window(source, 0)
            val claim = LocalMemoryRawClaim(source.text, "promise", "assistant", "affirm", "assistant:read_together",
                1.0, false, source.messageId == prefix.id, if (source.messageId == prefix.id) "cancelled" else "pending", null, null)
            val annotation = checkNotNull(LocalMemoryRecognitionRules.ground(window, claim))
            assertTrue(recognition.finish(job, window, listOf(annotation)) { true })
        }
        val original = LocalRecall(old.id, old.role, old.content, old.createdAt, "mid", .9)
        val result = LocalSemanticCorrections(archive).complete(owner, chat.id, listOf(original)).originals.single()
        assertEquals("The actual successor must be materialized", prefix.id, result.messageId)
        assertEquals(prefix.content, result.text)
        assertEquals("call_interrupted", result.origin)
        assertEquals("current_correction", result.evidenceState)
        assertEquals(listOf(old, prefix), archive.history(owner, chat.id).messages)
    }

    private fun hit(source: Message) = LocalSemanticHit(source.id, source.role, source.createdAt, "mid",
        LocalMemoryCodec.sha(LocalMemoryCodec.bytes(source.content)), 0, source.content.length,
        source.content.length, .9, 1L)
}
