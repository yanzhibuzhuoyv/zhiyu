package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Continues the exact synthetic archive left by the user-stopped five-million run. */
class FiftyMillionResumeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "fifty_million_a9571eab-dd7a-4e50-9ee1-06c3f94bcc16.sqlite3"
    private val owner = "synthetic-fifty-million"

    @Test fun resumeStoppedArchiveToFullSemanticCoverage() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val database = LocalChatDatabase(context, databaseName)
        val encoder = BgeLocalSemanticEncoder(context)
        val keyFile = File(context.filesDir, "zhiyu_native_probe/key")
        val modelFile = File(context.filesDir, "zhiyu_native_probe/model")
        var completed = false
        try {
            require(encoder.isAvailable()) { "Bundled BGE model is unavailable" }
            val conversation = database.findConversation(owner)
                ?: error("The stopped five-million archive is missing")
            require(countMessages(database, conversation.id) == 5000) { "Stopped archive message count changed" }
            val semantic = LocalSemanticService(database, encoder)
            val before = semantic.statistics(owner, conversation.id)
            require(before.totalMessages == 5000L && before.vectorChunks > 0) { "The stopped semantic checkpoint is missing" }
            nativeProbeReport("FIFTY_MILLION_RESUME_START ready=${before.readyMessages} chunks=${before.vectorChunks} expected=100000")
            var batches = 0
            while (true) {
                val batch = semantic.indexBatch(owner, conversation.id, 16)
                batches++
                val progress = semantic.statistics(owner, conversation.id)
                if (batches % 32 == 0 || progress.readyMessages == progress.totalMessages) {
                    nativeProbeReport("FIFTY_MILLION_RESUME_PROGRESS ready=${progress.readyMessages} " +
                        "messages=${progress.totalMessages} chunks=${progress.vectorChunks} expected=100000 " +
                        "failed=${progress.failedMessages} reason=${batch.reason}")
                }
                if (progress.readyMessages == progress.totalMessages) break
                check(batch.encodedChunks > 0) { "Resume indexing stopped before full coverage: ${batch.reason}" }
            }
            database.close()
            val restarted = LocalChatDatabase(context, databaseName)
            try {
                val reopened = LocalSemanticService(restarted, encoder)
                val stats = reopened.statistics(owner, conversation.id)
                check(stats.totalMessages == 5000L && stats.readyMessages == 5000L &&
                    stats.vectorChunks == 100000L && stats.failedMessages == 0L) { "Full semantic coverage failed after restart" }
                val history = loadAllMessages(restarted, conversation.id)
                check(history.size == 5000 && history.all { it.content.codePointCount(0, it.content.length) == 10000 }) {
                    "Original archive changed while completing semantic coverage"
                }
                val recent = restarted.promptHistory(owner, conversation.id)
                val proof = evaluate(reopened, conversation, recent, keyFile, modelFile)
                nativeProbeReport("FIFTY_MILLION_RESUME_REPORT " + JSONObject()
                    .put("messages", history.size).put("unicode_characters", history.sumOf { it.content.codePointCount(0, it.content.length) })
                    .put("semantic_ready_messages", stats.readyMessages).put("semantic_vector_chunks", stats.vectorChunks)
                    .put("semantic_failed_messages", stats.failedMessages).put("restart_semantic_coverage", "passed")
                    .put("queries", proof).put("official_provider_calls", if (keyFile.isFile) proof.length() else 0))
            } finally {
                restarted.close()
            }
            completed = true
        } finally {
            database.close()
            if (completed) context.deleteDatabase(databaseName)
            if (keyFile.exists()) check(keyFile.delete())
            if (modelFile.exists()) check(modelFile.delete())
        }
    }

    private fun countMessages(database: LocalChatDatabase, id: String): Int {
        var before: String? = null
        var count = 0
        do {
            val page = database.history(owner, id, before)
            count += page.messages.size
            before = page.nextBefore
        } while (before != null)
        return count
    }

    private fun loadAllMessages(database: LocalChatDatabase, id: String): List<Message> {
        var before: String? = null
        val all = mutableListOf<Message>()
        do {
            val page = database.history(owner, id, before)
            all += page.messages
            before = page.nextBefore
        } while (before != null)
        return all
    }

    private suspend fun evaluate(semantic: LocalSemanticService, conversation: Conversation, history: List<Message>,
                                 keyFile: File, modelFile: File): JSONArray {
        val queries = listOf(
            Triple("我生日是哪天，之前是不是纠正过？", listOf("九月十八", "9月18"), emptyList()),
            Triple("约好哪天在聊天里聊剧本？", listOf("十一月六", "11月6"), emptyList()),
            Triple("蓝桥我们约好哪天聊哪本书？", listOf("十二月八", "12月8"), listOf("春潮")),
            Triple("我喜欢哪种花？", listOf("铃兰"), emptyList()),
        )
        val result = JSONArray()
        queries.forEach { (query, facts, extraFacts) ->
            val started = System.nanoTime()
            val retrieval = semantic.retrieve(owner, conversation.id, query, history.takeLast(48).map { it.id }.toSet())
            val recalledText = retrieval.originals.joinToString(" ") { it.text }.replace(Regex("\\s+"), "")
            nativeProbeReport("FIFTY_MILLION_RESUME_RECALL question=$query originals=${retrieval.originals.size} " +
                "ids=${retrieval.originals.map { it.messageId }} contains=${facts.map { it to recalledText.contains(it) }}")
            check(facts.any(recalledText::contains) &&
                (extraFacts.isEmpty() || extraFacts.any(recalledText::contains))) {
                "Distant original recall failed for: $query"
            }
            val item = JSONObject().put("question", query).put("recall_ms", (System.nanoTime() - started) / 1_000_000.0)
                .put("route", retrieval.route).put("fallback_reason", retrieval.fallbackReason)
                .put("returned_originals", retrieval.originals.size)
                .put("independent_original_ids", retrieval.originals.map { it.messageId })
                .put("recall_fact", true)
            if (keyFile.isFile && modelFile.isFile) {
                val key = keyFile.readText().trim()
                val model = modelFile.readText().trim()
                val system = LocalCompanionPrompt.build(conversation.profile, retrieval.originals, emptyList())
                val answer = withTimeout(900_000) { DirectProviderClient().complete("deepseek", key, model,
                    listOf(DirectChatMessage("system", system)) + history.takeLast(48).map { DirectChatMessage(it.role, it.content) } +
                        DirectChatMessage("user", query), 32768) }
                val normalized = answer.replace(Regex("\\s+"), "")
                item.put("complete_answer_ms", (System.nanoTime() - started) / 1_000_000.0)
                    .put("answer_characters", answer.length)
                    .put("answer_fact", facts.any(normalized::contains) &&
                        (extraFacts.isEmpty() || extraFacts.any(normalized::contains)))
                    .put("natural_uncertainty", listOf("你没告诉过", "数据库里", "手头的记录", "检索不到").none(answer::contains))
                check(item.getBoolean("answer_fact") && item.getBoolean("natural_uncertainty")) {
                    "Official answer probe failed for: $query"
                }
            }
            result.put(item)
        }
        return result
    }
}
