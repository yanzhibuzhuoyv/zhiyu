package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated phone databases and synthetic originals; no provider calls or user database access. */
class LocalChatDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "local_archive_test_${UUID.randomUUID()}.sqlite3"
    private var db = LocalChatDatabase(context, name)
    private val owner = "synthetic-fixed-companion"
    private val chat by lazy { db.ensureConversation(owner, "synthetic-model", CompanionProfile()) }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun longUserAndAssistantTailRecallPreservesEveryOriginalAndSurvivesRestart() {
        val userText = "甲".repeat(16001) + " 我的生日是九月十八日 USER_TAIL_761"
        val assistantText = "乙".repeat(64000) + " 我答应十月八日陪你看电影 AI_TAIL_1761"
        val user = db.append(owner, chat.id, "user", userText)
        val assistant = db.append(owner, chat.id, "assistant", assistantText)
        val recalls = db.recall(owner, chat.id, "生日 USER_TAIL_761 电影 AI_TAIL_1761")
        assertTrue(recalls.any { it.messageId == user.id && it.role == "user" && it.text.contains("USER_TAIL_761") })
        assertTrue(recalls.any { it.messageId == assistant.id && it.role == "assistant" && it.text.contains("AI_TAIL_1761") })
        recalls.forEach { hit ->
            val source = if (hit.messageId == user.id) user else assistant
            assertEquals(source.createdAt, hit.createdAt)
            assertEquals(source.content.length, hit.originalChars)
            assertEquals(source.content.substring(hit.startOffset, hit.endOffset), hit.text)
        }
        val chatId = chat.id
        db.close(); db = LocalChatDatabase(context, name)
        assertEquals(listOf(user, assistant), db.history(owner, chatId).messages)
        db.readableDatabase.rawQuery("SELECT sha256,raw_bytes,length(payload) FROM messages ORDER BY seq", null).use {
            for (message in listOf(user, assistant)) {
                assertTrue(it.moveToNext())
                assertEquals(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(message.content)), it.getString(0))
                assertEquals(LocalMemoryCodec.bytes(message.content).size, it.getInt(1))
                assertTrue(it.getInt(2) < it.getInt(1))
            }
        }
    }
    @Test fun excludingTwentyFourRecentBirthdayMentionsDoesNotHideTheOldExplicitBirthday() {
        val original = db.append(owner, chat.id, "user", "我的生日是九月十八日。")
        val recentIds = List(30) { db.append(owner, chat.id, "user", "你记得我的生日吗？第${it}次提问").id }.toSet()
        val results = db.recall(owner, chat.id, "我的生日", recentIds)
        assertTrue(results.any { it.messageId == original.id && it.text.contains("九月十八") })
        assertTrue(results.none { it.messageId in recentIds })
    }
    @Test fun relevanceIsRankedBeforeRecentPermanentNoise() {
        val original = db.append(owner, chat.id, "user", "生日生日生日，蓝色木棉咖啡屋是我想去的地方。")
        repeat(180) {
            val noise = db.append(owner, chat.id, "user", "今天随口聊了生日，数字$it")
            db.changeMemory(owner, chat.id, noise.id, MemoryChange(0, tier = "permanent"))
        }
        val results = db.recall(owner, chat.id, "生日 蓝色木棉咖啡屋")
        assertEquals(original.id, results.first().messageId)
    }
    @Test fun repeatedMatchesInOneLongOriginalDoNotHideOtherIndependentOriginals() {
        val birthday = db.append(owner, chat.id, "user", "我的生日是九月十八日。")
        val repeated = db.append(owner, chat.id, "user", "生日讨论，天空很蓝。".repeat(6000))
        val results = db.recall(owner, chat.id, "生日")
        assertTrue(results.any { it.messageId == birthday.id && it.text.contains("九月十八日") })
        assertTrue(results.any { it.messageId == repeated.id })
        assertEquals(results.size, results.map { it.messageId }.distinct().size)
    }
    @Test fun paginationAndColdArchivePreserveIdsAuthorsAndOriginalText() {
        val originals = List(205) { db.append(owner, chat.id, if (it % 2 == 0) "user" else "assistant", "独立原文 $it 苹果🐈") }
        val latest = db.history(owner, chat.id)
        assertEquals(originals.takeLast(100), latest.messages)
        val middle = db.history(owner, chat.id, latest.nextBefore)
        val first = db.history(owner, chat.id, middle.nextBefore)
        assertEquals(originals, first.messages + middle.messages + latest.messages)
        assertFalse(first.hasMore)
        val old = originals.first()
        db.changeMemory(owner, chat.id, old.id, MemoryChange(0, indexState = "cold"))
        assertTrue(db.recall(owner, chat.id, "苹果", originals.drop(1).map { it.id }.toSet().take(100).toSet()).none { it.messageId == old.id })
        assertEquals(old.content, db.memoryEntry(owner, chat.id, old.id).text)
        db.changeMemory(owner, chat.id, old.id, MemoryChange(1, indexState = "active"))
        assertEquals("active", db.memoryEntry(owner, chat.id, old.id).indexState)
        assertEquals(409, assertThrows(ApiFailure::class.java) { db.changeMemory(owner, chat.id, old.id, MemoryChange(0, tier = "long")) }.status)
        assertEquals(404, assertThrows(ApiFailure::class.java) { db.history("foreign", chat.id) }.status)
    }
    @Test fun importedDocumentIsSearchableAndDeletionRemovesOnlyThatDocument() {
        val text = "普通资料🐈\n".repeat(700) + "生日是九月十八日 DOC_TAIL_761"
        val document = db.importDocument(owner, "合成.md", text)
        assertTrue(document.chunks > 1)
        val sources = db.documentSources(owner, "DOC_TAIL_761")
        assertEquals(document.id, sources.single().documentId)
        assertTrue(sources.single().text.contains("九月十八日"))
        assertTrue(db.documentSources("foreign", "DOC_TAIL_761").isEmpty())
        db.deleteDocument(owner, document.id)
        assertTrue(db.documentSources(owner, "DOC_TAIL_761").isEmpty())
        assertTrue(db.documents(owner).isEmpty())
    }
    @Test fun proactiveRequestIsPersistentlyIdempotentAndNeverCreatesAUserOriginal() {
        val request = UUID.randomUUID().toString()
        val first = db.append(owner, chat.id, "assistant", "有点想你。", "proactive", requestId = request)
        val repeated = db.append(owner, chat.id, "assistant", "不可重复插入的消息", "proactive", requestId = request)
        assertEquals(first, repeated)
        assertEquals(first, db.proactiveResult(owner, chat.id, request))
        assertEquals(listOf(first), db.history(owner, chat.id).messages)
        assertEquals("proactive", db.proactivePolicyHistory(owner, chat.id).single().origin)
    }
    @Test fun longRecentContextIsBoundedWithoutLosingTheCurrentUserOrOriginalHistory() {
        val longReply = db.append(owner, chat.id, "assistant", "完整原文🐈".repeat(30000) + "AI尾标")
        val current = db.append(owner, chat.id, "user", "  我的这一句原文和空格完整保留。\n")
        val context = db.promptContext(owner, chat.id, 2000)
        assertEquals(current, context.messages.last())
        assertEquals(1, context.messages.count { it.id == current.id })
        assertTrue(context.messages.first().content.contains("AI尾标"))
        assertTrue(context.messages.first().content.contains("UTF16范围="))
        assertTrue(context.messages.sumOf { it.content.length } <= 2000)
        assertEquals(setOf(current.id), context.completeMessageIds)
        assertTrue(db.mediaHistory(owner, chat.id).all { it.content.isEmpty() })
        assertEquals(listOf(longReply, current), db.history(owner, chat.id).messages)
    }
    @Test fun historyByteBudgetPagesLargeOriginalsWithoutTruncatingAnyBody() {
        val originals = List(4) { db.append(owner, chat.id, "assistant", "x".repeat(800000) + " END_$it") }
        val last = db.history(owner, chat.id)
        assertTrue(last.hasMore)
        assertEquals(originals.takeLast(2), last.messages)
        val first = db.history(owner, chat.id, last.nextBefore)
        assertFalse(first.hasMore)
        assertEquals(originals.take(2), first.messages)
        assertEquals(originals, first.messages + last.messages)
    }
}
