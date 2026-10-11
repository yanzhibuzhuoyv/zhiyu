package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Separate 50,000,000-code-point corpus; complete originals and model-facing recall are checked. */
class FiftyMillionMemoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val anchors = mapOf(
        0 to "我的生日是六月十日",
        1 to "我猜你生日可能是四月二十日这只是猜测",
        2 to "我特别喜欢铃兰",
        4 to "我们约好十一月六日一起在聊天里聊剧本",
        8 to "我叫林岚蓝桥是我最喜欢的书店",
        2500 to "之前生日那个日期说错了我的生日实际是九月十八日",
        2502 to "我答应十二月八日在蓝桥聊春潮这本书",
        4998 to "我现在仍然最喜欢铃兰",
    )
    @Test fun exactFiftyMillionCharactersSurviveRestartAndDistantRecall() = runBlocking {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val name = "fifty_million_${UUID.randomUUID()}.sqlite3"
        val owner = "synthetic-fifty-million"
        var database = LocalChatDatabase(context, name)
        val encoder = BgeLocalSemanticEncoder(context)
        val started = System.nanoTime()
        val keyFile = File(context.filesDir, "zhiyu_native_probe/key")
        val modelFile = File(context.filesDir, "zhiyu_native_probe/model")
        try {
            val chat = database.ensureConversation(owner, "synthetic-model", CompanionProfile(style = "warm_v2"))
            assertTrue("Bundled real semantic model must be available", encoder.isAvailable())
            val archive = populate(database, owner, chat.id, encoder)
            assertEquals(50_000_000L, archive.characters)
            database.close(); database = LocalChatDatabase(context, name)
            val count = verifyRestart(database, owner, chat.id, archive)
            assertEquals(5000, count)
            val indexStarted = System.nanoTime()
            indexCorpus(LocalSemanticService(database, encoder), owner, chat.id, archive)
            val indexSeconds = (System.nanoTime() - indexStarted) / 1_000_000_000.0
            database.close(); database = LocalChatDatabase(context, name)
            val semantic = LocalSemanticService(database, encoder)
            val statistics = semantic.statistics(owner, chat.id)
            assertEquals(5000L, statistics.readyMessages)
            assertEquals(archive.expectedChunks, statistics.vectorChunks)
            assertEquals(0L, statistics.pendingMessages)
            val recent = database.promptHistory(owner, chat.id)
            assertTrue(recent.sumOf { it.content.length } <= 48000)
            val cloud = keyFile.isFile && modelFile.isFile
            val key = if (cloud) keyFile.readText().trim() else ""
            val model = if (cloud) modelFile.readText().trim() else ""
            val proof = evaluate(semantic, owner, chat, archive.ids, recent, key, model)
            report(name, archive, count, started, recent, cloud, proof, indexSeconds, statistics)
            assertProbes(proof, cloud)
        } finally {
            database.close(); context.deleteDatabase(name)
            if (keyFile.exists()) check(keyFile.delete())
            if (modelFile.exists()) check(modelFile.delete())
        }
    }
    private data class Archive(val ids: List<String>, val hashes: List<ByteArray>, val characters: Long,
                               val rawBytes: Long, val expectedChunks: Long)
    private fun populate(database: LocalChatDatabase, owner: String, id: String, encoder: LocalSemanticEncoder): Archive {
        val ids = ArrayList<String>(5000)
        val hashes = ArrayList<ByteArray>(5000)
        var characters = 0L
        var rawBytes = 0L
        var expectedChunks = 0L
        for (index in 0 until 5000) {
            val text = original(index)
            assertEquals(10000, text.codePointCount(0, text.length))
            assertEquals(10000, hanCount(text))
            val message = database.append(owner, id, if (index % 2 == 0) "user" else "assistant", text)
            ids.add(message.id); hashes.add(hash(message))
            characters += text.codePointCount(0, text.length)
            rawBytes += text.toByteArray().size
            expectedChunks += encoder.documentChunkCount(text)
            if ((index + 1) % 1000 == 0) nativeProbeReport("FIFTY_MILLION_PROGRESS characters=$characters messages=${index + 1}")
        }
        return Archive(ids, hashes, characters, rawBytes, expectedChunks)
    }
    private suspend fun indexCorpus(semantic: LocalSemanticService, owner: String, id: String, archive: Archive) {
        var batches = 0
        while (true) {
            val batch = semantic.indexBatch(owner, id, 16)
            batches++
            if (batches % 32 == 0 || batch.encodedChunks == 0) {
                val progress = semantic.statistics(owner, id)
                nativeProbeReport("FIFTY_MILLION_SEMANTIC_PROGRESS ready=${progress.readyMessages} " +
                    "messages=${progress.totalMessages} chunks=${progress.vectorChunks} expected=${archive.expectedChunks} " +
                    "failed=${progress.failedMessages} reason=${batch.reason}")
                if (progress.readyMessages == progress.totalMessages) return
            }
            check(batch.encodedChunks > 0) { "Complete-corpus semantic indexing stopped before coverage was complete" }
        }
    }
    private fun verifyRestart(database: LocalChatDatabase, owner: String, id: String, archive: Archive): Int {
        var before: String? = null
        var count = 0
        do {
            val page = database.history(owner, id, before)
            page.messages.forEachIndexed { offset, message ->
                val index = 5000 - count - page.messages.size + offset
                assertEquals(archive.ids[index], message.id)
                assertEquals(original(index), message.content)
                assertArrayEquals(archive.hashes[index], hash(message))
            }
            count += page.messages.size; before = page.nextBefore
        } while (before != null)
        return count
    }
    private val queries = listOf(
        Probe("我生日是哪天，之前是不是纠正过？", listOf(0, 2500), listOf("九月十八", "9月18")),
        Probe("约好哪天在聊天里聊剧本？", listOf(4), listOf("十一月六", "11月6")),
        Probe("蓝桥我们约好哪天聊哪本书？", listOf(8, 2502), listOf("春潮"), listOf("十二月八", "12月8")),
        Probe("我喜欢哪种花？", listOf(2, 4998), listOf("铃兰")),
    )
    private suspend fun evaluate(semantic: LocalSemanticService, owner: String, chat: Conversation,
                                 ids: List<String>, recent: List<Message>, key: String, model: String): JSONArray {
        val proof = JSONArray()
        for (probe in queries) {
            val searchStart = System.nanoTime()
            val retrieval = semantic.retrieve(owner, chat.id, probe.question, recent.map { it.id }.toSet())
            val recalled = retrieval.originals
            val searchMs = (System.nanoTime() - searchStart) / 1_000_000.0
            val available = recalled.map { it.messageId }.toSet() + recent.map { it.id }
            val item = JSONObject().put("question", probe.question).put("recall_ms", searchMs)
                .put("independent_original_recall", probe.indices.all { ids[it] in available })
                .put("returned_originals", recalled.size).put("route", retrieval.route)
                .put("fallback_reason", retrieval.fallbackReason)
            proof.put(item)
            if (key.isNotEmpty()) evaluateAnswer(chat, recent, recalled, probe, item, key, model)
        }
        return proof
    }
    private suspend fun evaluateAnswer(chat: Conversation, recent: List<Message>, recalled: List<LocalRecall>,
                                       probe: Probe, item: JSONObject, key: String, model: String) {
        val answerStart = System.nanoTime()
        val system = LocalCompanionPrompt.build(chat.profile, recalled, emptyList())
        val answer = withTimeout(900_000) { DirectProviderClient().complete("deepseek", key, model,
            listOf(DirectChatMessage("system", system)) + recent.map { DirectChatMessage(it.role, it.content) } +
                DirectChatMessage("user", probe.question), 32768) }
        val normalized = answer.replace(Regex("\\s+"), "")
        item.put("complete_answer_ms", (System.nanoTime() - answerStart) / 1_000_000.0)
            .put("answer_characters", answer.length)
            .put("answer_fact", probe.alternatives.any(normalized::contains) &&
                (probe.extraAlternatives.isEmpty() || probe.extraAlternatives.any(normalized::contains)))
            .put("natural_uncertainty", listOf("你没告诉过", "你没讲过", "数据库里", "手头的记录", "检索不到").none(answer::contains))
    }
    private fun report(name: String, archive: Archive, count: Int, started: Long,
                       recent: List<Message>, cloud: Boolean, proof: JSONArray, indexSeconds: Double,
                       statistics: LocalSemanticStatistics) {
            val main = context.getDatabasePath(name)
            val wal = File(main.absolutePath + "-wal").length()
            val shm = File(main.absolutePath + "-shm").length()
        val report = JSONObject().put("unicode_characters", archive.characters).put("han_characters", archive.characters)
                .put("non_han_codepoints", 0).put("rounds", 2500).put("messages", count)
                .put("original_utf8_bytes", archive.rawBytes).put("database_bytes", main.length())
                .put("wal_bytes", wal).put("shared_memory_bytes", shm).put("total_storage_bytes", main.length() + wal + shm)
                .put("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000.0)
                .put("restart_full_id_role_time_body_sha", "passed").put("memory_fusion", false)
                .put("semantic_index_seconds", indexSeconds).put("semantic_ready_messages", statistics.readyMessages)
                .put("semantic_vector_chunks", statistics.vectorChunks).put("semantic_expected_chunks", archive.expectedChunks)
                .put("semantic_failed_messages", statistics.failedMessages).put("semantic_restart_coverage", "passed")
                .put("model_recent_context_characters", recent.sumOf { it.content.length })
                .put("official_provider_calls", if (cloud) queries.size else 0).put("probes", proof)
            nativeProbeReport("FIFTY_MILLION_REPORT $report")
    }
    private fun assertProbes(proof: JSONArray, cloud: Boolean) {
            for (index in 0 until proof.length()) {
                val item = proof.getJSONObject(index)
                assertTrue("Independent original recall failed for probe $index", item.getBoolean("independent_original_recall"))
                if (cloud) { assertTrue("Model fact failed for probe $index", item.getBoolean("answer_fact"))
                    assertTrue("Model presentation failed for probe $index", item.getBoolean("natural_uncertainty")) }
            }
    }
    private data class Probe(val question: String, val indices: List<Int>, val alternatives: List<String>, val extraAlternatives: List<String> = emptyList())
    private fun original(index: Int): String {
        val random = Random(20261009 xor index)
        val letters = "春雨清风树影云光远山花草街角灯火晨雾晚霞小路窗边海岸青石暮色田野河流"
        val text = StringBuilder()
        while (text.length < 10000) {
            text.append("今天走过街角远处的天色慢慢变亮路边的树叶在风里轻轻摇动我想与你分享这些平常的片刻")
            repeat(24) { text.append(letters[random.nextInt(letters.length)]) }
            text.append("这段文字独立保存不替代别人的话")
        }
        val suffix = anchors[index].orEmpty()
        return text.substring(0, 10000 - suffix.length) + suffix
    }
    private fun hanCount(text: String): Int = text.codePoints().toArray().count {
        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
    }
    private fun hash(message: Message): ByteArray = MessageDigest.getInstance("SHA-256").run {
        listOf(message.id, message.role, message.content, message.createdAt).forEach { update(it.toByteArray()); update(0) }
        digest()
    }
}
