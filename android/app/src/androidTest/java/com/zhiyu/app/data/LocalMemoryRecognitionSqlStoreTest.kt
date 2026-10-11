package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.MemoryChange
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** UUID archive, synthetic exact originals and fake model labels; no Key/cloud/user data. */
class LocalMemoryRecognitionSqlStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "native_recognition_test_${UUID.randomUUID()}.sqlite3"
    private var database = LocalChatDatabase(context, name)
    private val owner = "synthetic-recognition-owner"
    private val chat by lazy { database.ensureConversation(owner, "synthetic-bound-model", null) }
    private val identity get() = LocalMemoryRecognitionIdentity(owner, chat.id, "deepseek", chat.model)
    private var clock = 1_000_000L
    private var store = LocalMemoryRecognitionSqlStore(database) { clock }
    @After fun cleanup() { database.close(); context.deleteDatabase(name) }

    @Test fun annotationPersistsOneExactSpanAndFullOriginalThroughRestart() {
        val message = database.append(owner, chat.id, "user", "我是我，生日是九月十八日，🐈这是原话。")
        val job = claim(); val original = store.original(job)!!; val window = LocalMemoryRecognitionRules.window(original, 0)
        val annotation = profile(window, "生日是九月十八日")
        assertTrue(store.finish(job, window, listOf(annotation)) { true })
        assertEquals("permanent", database.memoryEntry(owner, chat.id, message.id).tier)
        reopen()
        val saved = database.recognitionOriginal(owner, chat.id, message.id)!!
        assertEquals(original.text, saved.text); assertEquals(original.sha256, saved.sha256)
        assertEquals(original.createdAt, saved.createdAt); assertEquals(1, count("native_memory_annotations"))
        assertEquals(annotation.spanSha, scalar("SELECT span_sha FROM native_memory_annotations"))
        assertEquals("ok", scalar("SELECT status FROM native_memory_jobs"))
    }
    @Test fun revokedAuthorizationRollsBackAnnotationTierAndColdIndexRestorationTogether() {
        val message = database.append(owner, chat.id, "user", "我的生日是九月十八日")
        database.changeMemory(owner, chat.id, message.id, MemoryChange(0, indexState = "cold"))
        val job = claim(); val original = store.original(job)!!; val window = LocalMemoryRecognitionRules.window(original, 0)
        val checks = AtomicInteger()
        assertThrows(LocalMemoryRecognitionPaused::class.java) {
            store.finish(job, window, listOf(profile(window, window.text))) { checks.incrementAndGet() == 1 }
        }
        assertEquals(0, count("native_memory_annotations")); assertEquals("running", scalar("SELECT status FROM native_memory_jobs"))
        val current = database.recognitionOriginal(owner, chat.id, message.id)!!
        assertEquals("cold", current.indexState); assertEquals(original.revision, current.revision)
        assertEquals(original.sha256, current.sha256); assertEquals(original.text, current.text)
        assertEquals(0, count("memory_fts")); assertTrue(count("memory_cold_fts") > 0)
        store.release(job, true); assertEquals("waiting_auth", scalar("SELECT status FROM native_memory_jobs"))
    }
    @Test fun pendingColdPromiseReturnsToActiveWithoutChangingSourceOrAuthor() {
        val message = database.append(owner, chat.id, "assistant", "我答应明天陪你去吃火锅。")
        database.changeMemory(owner, chat.id, message.id, MemoryChange(0, indexState = "cold"))
        val job = claim(); val original = store.original(job)!!; val window = LocalMemoryRecognitionRules.window(original, 0)
        val claim = LocalMemoryRecognitionRules.ground(window, LocalMemoryRawClaim(window.text, "promise", "assistant",
            "affirm", "promise:hotpot", .95, false, false, "pending", null, null))!!
        assertTrue(store.finish(job, window, listOf(claim)) { true })
        val current = database.recognitionOriginal(owner, chat.id, message.id)!!
        assertEquals("active", current.indexState); assertEquals(original.text, current.text)
        assertEquals(original.sha256, current.sha256); assertEquals(original.role, current.role)
        assertEquals("mid", database.memoryEntry(owner, chat.id, message.id).tier)
        assertTrue(database.memoryEntry(owner, chat.id, message.id).pinned)
        assertEquals(0, count("memory_cold_fts")); assertTrue(count("memory_fts") > 0)
    }
    @Test fun explicitCorrectionSupersedesOnlyMatchingEarlierProfileAndNeverDeletesIt() {
        val old = database.append(owner, chat.id, "user", "我的生日是九月十八日")
        val first = claim(); val source = store.original(first)!!; val firstWindow = LocalMemoryRecognitionRules.window(source, 0)
        assertTrue(store.finish(first, firstWindow, listOf(profile(firstWindow, firstWindow.text))) { true })
        val corrected = database.append(owner, chat.id, "user", "更正，我的生日其实是九月十九日")
        val second = claim(); val current = store.original(second)!!; val secondWindow = LocalMemoryRecognitionRules.window(current, 0)
        assertTrue(store.finish(second, secondWindow, listOf(profile(secondWindow, secondWindow.text, true))) { true })
        assertEquals("superseded", scalar("SELECT validity FROM native_memory_annotations WHERE message_id=?", old.id))
        assertEquals("current", scalar("SELECT validity FROM native_memory_annotations WHERE message_id=?", corrected.id))
        assertEquals(1, count("native_memory_annotation_links"))
        assertEquals(source.text, database.recognitionOriginal(owner, chat.id, old.id)!!.text)
        assertEquals(source.sha256, database.recognitionOriginal(owner, chat.id, old.id)!!.sha256)
        assertEquals("permanent", database.memoryEntry(owner, chat.id, corrected.id).tier)
    }
    @Test fun longWindowCursorSurvivesRestartAndExpiredLeaseNeverCommits() {
        val text = "甲".repeat(15999) + "🐈" + "乙".repeat(17000) + "生日尾句"
        val message = database.append(owner, chat.id, "user", text)
        val first = claim(); val source = store.original(first)!!; val window = LocalMemoryRecognitionRules.window(source, 0)
        assertEquals(15999, window.end); assertTrue(store.finish(first, window, emptyList()) { true })
        reopen()
        val second = claim(); assertEquals(15999, second.offset)
        val remaining = LocalMemoryRecognitionRules.window(store.original(second)!!, second.offset)
        clock += 180_001; store.recover(identity)
        assertFalse(store.finish(second, remaining, emptyList()) { true })
        val recovered = store.claim(identity)!!; assertNotEquals(second.lease, recovered.lease)
        assertEquals(second.offset, recovered.offset)
        assertTrue(store.finish(recovered, remaining, emptyList()) { true })
        val last = store.claim(identity)!!; val tail = LocalMemoryRecognitionRules.window(store.original(last)!!, last.offset)
        assertTrue(tail.text.endsWith("生日尾句")); assertTrue(store.finish(last, tail, emptyList()) { true })
        assertFalse(store.pending(identity)); assertEquals(text, database.recognitionOriginal(owner, chat.id, message.id)!!.text)
    }
    @Test fun foreignScopeWrongLeaseAndNewSourceRevisionCannotWriteAnyAnnotation() {
        val message = database.append(owner, chat.id, "user", "更正，我的生日是九月十八日")
        val job = claim(); val source = store.original(job)!!; val window = LocalMemoryRecognitionRules.window(source, 0)
        val annotations = listOf(profile(window, window.text))
        assertFalse(store.finish(job.copy(owner = "foreign"), window, annotations) { true })
        assertFalse(store.finish(job.copy(lease = "wrong"), window, annotations) { true })
        assertNull(store.claim(identity.copy(model = "foreign-model")))
        database.changeMemory(owner, chat.id, message.id, MemoryChange(0, tier = "long"))
        assertFalse(store.finish(job, window, annotations) { true }); assertEquals(0, count("native_memory_annotations"))
        store.release(job, true)
        val next = store.claim(identity)!!; assertEquals(1, next.sourceRevision)
        val currentWindow = LocalMemoryRecognitionRules.window(store.original(next)!!, 0)
        assertTrue(store.finish(next, currentWindow, listOf(profile(currentWindow, currentWindow.text, true))) { true })
        assertEquals("long", database.memoryEntry(owner, chat.id, message.id).tier)
        assertTrue(database.memoryEntry(owner, chat.id, message.id).pinned)
    }
    @Test fun fabricatedSpanAndSkippedWindowCannotAdvanceDurableCursor() {
        database.append(owner, chat.id, "user", "甲".repeat(17000) + "生日是九月十八日")
        val job = claim(); val source = store.original(job)!!; val first = LocalMemoryRecognitionRules.window(source, 0)
        val tail = LocalMemoryRecognitionRules.window(source, first.end)
        val annotation = profile(tail, "生日是九月十八日")
        assertThrows(IllegalArgumentException::class.java) { store.finish(job, first, listOf(annotation)) { true } }
        assertThrows(IllegalArgumentException::class.java) { store.finish(job, first.copy(end = 100), emptyList()) { true } }
        assertEquals("0", scalar("SELECT source_offset FROM native_memory_jobs")); assertEquals(0, count("native_memory_annotations"))
    }
    private fun claim(): LocalMemoryRecognitionJob { store.enqueue(identity); return store.claim(identity)!! }
    private fun profile(window: LocalMemoryWindow, quote: String, correction: Boolean = false) =
        LocalMemoryRecognitionRules.ground(window, LocalMemoryRawClaim(quote, "profile", "user", "affirm",
            "user:birthday", .95, false, correction, null, null, null))!!
    private fun reopen() { database.close(); database = LocalChatDatabase(context, name); store = LocalMemoryRecognitionSqlStore(database) { clock } }
    private fun count(table: String): Int = database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun scalar(sql: String, vararg arguments: String): String = database.readableDatabase.rawQuery(sql, arguments).use { assertTrue(it.moveToFirst()); it.getString(0) }
}
