package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.domain.Message
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** New phone database only: long bodies, full archive verification, restart and distant recall. */
class LocalMemoryEnduranceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun longOriginalsSurviveManyRoundsAndRestartWithoutMerging() {
        val rounds = InstrumentationRegistry.getArguments().getString("localRounds", "1000").toInt()
        require(rounds in 1000..10000)
        val name = "local_endurance_${UUID.randomUUID()}.sqlite3"
        val owner = "synthetic-long-owner"
        var database = LocalChatDatabase(context, name)
        val started = System.nanoTime()
        try {
            val chat = database.ensureConversation(owner, "synthetic-model", CompanionProfile(style = "warm_v2"))
            val archive = populate(database, owner, chat.id, rounds)
            database.close()
            database = LocalChatDatabase(context, name)
            val count = verifyRestart(database, owner, chat.id, archive)
            assertEquals(rounds * 2, count)
            val recallStarted = System.nanoTime()
            val recalled = database.recall(owner, chat.id, "我生日哪天 LONG-BIRTHDAY-761")
            val recallMs = (System.nanoTime() - recallStarted) / 1_000_000.0
            assertTrue(recalled.any { it.messageId == archive.ids[14] && it.text.contains("九月十八") })
            val foreign = assertThrows(com.zhiyu.app.domain.ApiFailure::class.java) { database.history("foreign-owner", chat.id) }
            assertEquals(404, foreign.status)
            val main = context.getDatabasePath(name)
            val wal = java.io.File(main.absolutePath + "-wal").length()
            val sharedMemory = java.io.File(main.absolutePath + "-shm").length()
            val report = JSONObject().put("rounds", rounds).put("messages", count).put("original_utf8_bytes", archive.rawBytes)
                .put("database_bytes", main.length()).put("wal_bytes", wal).put("shared_memory_bytes", sharedMemory)
                .put("total_storage_bytes", main.length() + wal + sharedMemory).put("recall_ms", recallMs)
                .put("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000.0)
                .put("restart_and_full_original_hash", "passed").put("provider_calls", 0)
            nativeProbeReport("LOCAL_ENDURANCE_REPORT $report")
        } finally { database.close(); context.deleteDatabase(name) }
    }
    private data class Archive(val ids: List<String>, val hashes: List<ByteArray>, val rawBytes: Long)
    private fun populate(database: LocalChatDatabase, owner: String, id: String, rounds: Int): Archive {
        val ids = ArrayList<String>(rounds * 2)
        val hashes = ArrayList<ByteArray>(rounds * 2)
        var rawBytes = 0L
        for (round in 0 until rounds) {
            for (role in listOf("user", "assistant")) {
                val content = body(round, role)
                val row = database.append(owner, id, role, content)
                assertEquals(content, row.content)
                ids.add(row.id); hashes.add(hashMessage(row))
                rawBytes += content.toByteArray().size
            }
            if ((round + 1) % 1000 == 0) nativeProbeReport("LOCAL_ENDURANCE_PROGRESS rounds=${round + 1}")
        }
        return Archive(ids, hashes, rawBytes)
    }
    private fun verifyRestart(database: LocalChatDatabase, owner: String, id: String, archive: Archive): Int {
        var before: String? = null
        var count = 0
        do {
            val page = database.history(owner, id, before)
            page.messages.forEachIndexed { offset, message ->
                val index = archive.ids.size - count - page.messages.size + offset
                assertEquals(archive.ids[index], message.id)
                assertEquals(body(index / 2, if (index % 2 == 0) "user" else "assistant"), message.content)
                assertArrayEquals(archive.hashes[index], hashMessage(message))
            }
            count += page.messages.size; before = page.nextBefore
        } while (before != null)
        return count
    }
    private fun body(round: Int, role: String): String {
        val random = Random(20261009 xor round xor if (role == "user") 761 else 1761)
        val desired = if (role == "user") 2400 + (round % 1600) else 4800 + (round % 3200)
        val text = StringBuilder("第${round}次${role}日常：今天看到了不一样的天空。")
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        while (text.length < desired) {
            text.append("街角的咖啡和花园让我想与你分享 ")
            repeat(25) { text.append(alphabet[random.nextInt(alphabet.length)]) }
            text.append(" ，还记得那阵风。")
        }
        if (round == 7 && role == "user") text.append(" 我的生日是九月十八日。LONG-BIRTHDAY-761")
        if (round % 997 == 0) text.append(" 🍎 跨字符原文与角色小尾巴。").append(round)
        return text.toString()
    }
    private fun hash(vararg fields: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { value -> digest.update(value.toByteArray()); digest.update(0) }
        return digest.digest()
    }
    private fun hashMessage(message: Message) = hash(message.id, message.role, message.content, message.createdAt)
}
