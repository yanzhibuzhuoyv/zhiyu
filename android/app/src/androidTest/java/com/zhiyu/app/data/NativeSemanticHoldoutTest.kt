package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Frozen unseen wording; no lexical trigger, cloud request, private history or fabricated vectors. */
class NativeSemanticHoldoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun naturalParaphrasesReachIndependentOriginalsThroughTheRealMobileModel() = runBlocking {
        val cases = instrumentation.context.assets.open("native-semantic-holdout.json").use {
            JSONObject(it.readBytes().toString(Charsets.UTF_8))
        }
        val name = "semantic_holdout_${UUID.randomUUID()}.sqlite3"
        val owner = "synthetic-semantic-holdout"
        val database = LocalChatDatabase(context, name)
        val started = System.nanoTime()
        try {
            val chat = database.ensureConversation(owner, "synthetic-model", null)
            val ids = originals(database, owner, chat.id, cases.getJSONArray("documents"))
            val encoder = BgeLocalSemanticEncoder(context)
            val service = LocalSemanticService(database, encoder)
            var completed = 0
            while (completed < ids.size) {
                val batch = service.indexBatch(owner, chat.id)
                completed += batch.completedMessages
                check(batch.reason != "encoding_failed" && batch.encodedChunks > 0) { "Real mobile indexing stopped before corpus completion" }
            }
            val proof = JSONArray()
            val queries = cases.getJSONArray("queries")
            for (index in 0 until queries.length()) {
                val row = queries.getJSONArray(index)
                val query = row.getString(0)
                assertFalse("Legacy keyword gate should not explain this retrieval", LocalMemoryRules.shouldRetrieve(query))
                val start = System.nanoTime()
                val result = service.retrieve(owner, chat.id, query)
                val hit = result.originals.indexOfFirst { it.messageId == ids.getValue(row.getString(1)) }
                proof.put(JSONObject().put("index", index).put("semantic_only", result.route == "semantic")
                    .put("semantic_candidates", result.route in setOf("semantic", "semantic_cold", "semantic_then_lexical"))
                    .put("route", result.route)
                    .put("gold_rank", hit + 1).put("recall_ms", (System.nanoTime() - start) / 1_000_000.0)
                    .put("originals", result.originals.size).put("fallback_reason", result.fallbackReason ?: JSONObject.NULL))
            }
            nativeProbeReport("NATIVE_SEMANTIC_HOLDOUT_REPORT " + JSONObject().put("documents", ids.size)
                .put("model_identity", encoder.modelIdentity).put("elapsed_seconds", (System.nanoTime() - started) / 1e9)
                .put("provider_calls", 0).put("probes", proof))
            for (index in 0 until proof.length()) {
                assertTrue("Real semantic candidates missing for heldout $index", proof.getJSONObject(index).getBoolean("semantic_candidates"))
                assertTrue("Gold original missing for heldout $index", proof.getJSONObject(index).getInt("gold_rank") in 1..8)
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
    private fun originals(database: LocalChatDatabase, owner: String, chat: String, documents: JSONArray): Map<String, String> =
        buildMap { for (index in 0 until documents.length()) {
            val row = documents.getJSONArray(index)
            val message = database.append(owner, chat, "user", row.getString(1))
            assertEquals(row.getString(1), message.content)
            put(row.getString(0), message.id)
        } }
}
