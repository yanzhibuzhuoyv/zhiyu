package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Native optimizer regression: MATCH must drive the production join, even for large owner indexes. */
class LocalRecallQueryPlanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sparseFtsHitsDriveIndexedOriginalLookupAndKeepRecallContracts() {
        val name = "local_fts_plan_${UUID.randomUUID()}.sqlite3"
        val database = LocalChatDatabase(context, name)
        val owner = "synthetic-query-plan-owner"
        try {
            val chat = database.ensureConversation(owner, "synthetic-model", CompanionProfile())
            val older = database.append(owner, chat.id, "user", "我的生日是九月十八日。")
            val foreignChat = database.ensureConversation("foreign", "synthetic-model", CompanionProfile())
            database.append("foreign", foreignChat.id, "user", "生日是虚构日期，不能混入另一位用户。")
            seedNoise(database, owner, chat.id, 20000)
            val excluded = List(30) { database.append(owner, chat.id, "user", "你记得我的生日吗？提问$it").id }.toSet()
            val plan = database.recallCandidatePlan(owner, chat.id, "生日", excluded)
            val fts = plan.indexOfFirst { it.contains("SCAN f VIRTUAL TABLE") }
            val original = plan.indexOfFirst { it.contains("SEARCH m USING INDEX") }
            nativeProbeReport("LOCAL_FTS_PLAN ${JSONObject().put("messages", 20032).put("plan", JSONArray(plan))}")
            assertTrue("MATCH did not drive original lookup: $plan", fts >= 0 && original > fts)
            assertTrue("Original ID index not used: $plan", plan[original].contains("id=?"))
            val baseline = baselineCandidates(database, owner, chat.id, excluded)
            val started = System.nanoTime()
            val results = database.recall(owner, chat.id, "生日", excluded)
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            assertEquals(listOf(older.id), results.map { it.messageId })
            assertEquals(older.content.substring(results.single().startOffset, results.single().endOffset), results.single().text)
            assertEquals(older.createdAt, results.single().createdAt)
            assertTrue(database.recall("foreign", chat.id, "生日").isEmpty())
            assertEquals(baseline.first, results.map { it.messageId })
            nativeProbeReport("LOCAL_FTS_SPARSE_REPORT ${JSONObject().put("messages", 20032).put("recall_ms", elapsed)
                .put("baseline_candidate_ms", baseline.second).put("excluded_before_limit", 30)
                .put("independent_originals", results.size).put("provider_calls", 0)}")
        } finally { database.close(); context.deleteDatabase(name) }
    }
    private fun baselineCandidates(database: LocalChatDatabase, owner: String, id: String, exclude: Set<String>): Pair<List<String>, Double> {
        val excluded = " AND m.id NOT IN (${exclude.joinToString(",") { "?" }})"
        val sql = "SELECT m.id,MAX(CASE WHEN instr(' '||f.body_tokens||' ',?)>0 THEN 1 ELSE 0 END) AS relevance FROM memory_fts f JOIN messages m ON m.id=f.message_id WHERE f.body_tokens MATCH ? AND m.owner=? AND m.conversation_id=? AND m.index_state='active'$excluded GROUP BY m.id,m.role,m.created_at,m.tier,m.seq ORDER BY relevance DESC,CASE m.role WHEN 'user' THEN 0 ELSE 1 END,CASE m.tier WHEN 'permanent' THEN 0 WHEN 'long' THEN 1 ELSE 2 END,m.seq DESC LIMIT 8"
        val args = (listOf(" 生日 ", "\"生日\"", owner, id) + exclude.toList()).toTypedArray()
        val started = System.nanoTime()
        val ids = database.readableDatabase.rawQuery(sql, args).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        } }
        return ids to (System.nanoTime() - started) / 1_000_000.0
    }
    private fun seedNoise(database: LocalChatDatabase, owner: String, id: String, count: Int) {
        val text = "风景小猫日常"
        val encoded = LocalMemoryCodec.encode(text)
        val sql = database.writableDatabase
        sql.beginTransaction()
        try {
            sql.compileStatement("INSERT INTO messages(id,owner,conversation_id,role,payload,raw_bytes,sha256,created_at,origin,sources,attachments) VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { message ->
                sql.compileStatement("INSERT INTO memory_fts(body_tokens,owner,message_id,document_id,part_index,start_offset,end_offset,original_chars) VALUES(?,?,?,?,?,?,?,?)").use { part ->
                    repeat(count) { index ->
                        val identifier = "synthetic-noise-$index"
                        message.bindString(1, identifier); message.bindString(2, owner); message.bindString(3, id)
                        message.bindString(4, "user"); message.bindBlob(5, encoded.compressed); message.bindLong(6, encoded.utf8Bytes.toLong())
                        message.bindString(7, encoded.sha256); message.bindString(8, "2026-10-01T00:00:00Z")
                        message.bindString(9, "reply"); message.bindString(10, "[]"); message.bindString(11, "[]"); message.executeInsert()
                        part.bindString(1, LocalMemoryRules.terms(text).joinToString(" ")); part.bindString(2, owner)
                        part.bindString(3, identifier); part.bindString(4, ""); part.bindString(5, "0"); part.bindString(6, "0")
                        part.bindString(7, text.length.toString()); part.bindString(8, text.length.toString()); part.executeInsert()
                    }
                }
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }
}
