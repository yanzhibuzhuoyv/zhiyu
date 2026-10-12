package com.zhiyu.app.data.local.qwen

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.MediaRules
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.UUID

data class QwenImageJob(val id: String, val chat: String, val day: String, val version: Int,
    val referenceId: String?, val referenceSha: String?, val scene: String, val slot: String,
    val activity: String, val tags: List<String>, val seed: Long, val state: String = "prompt_reserved",
    val prompt: String = "", val attempt: Int = 0, val tokens: Int = 0, val condition: String? = null,
    val checkpoint: String? = null, val decoded: String? = null, val decodedSha: String? = null,
    val failures: Int = 0)

/** Only derived image jobs. Original OC, chat attachments and decoded outputs are never swept. */
class QwenImageJobStore(context: Context) {
    private val app = context.applicationContext ?: context
    val root = File(app.filesDir.canonicalFile, "qwen_jobs").apply {
        require(canonicalFile == absoluteFile && !Files.isSymbolicLink(toPath()))
        check(mkdirs() || isDirectory)
    }
    fun directory(id: String): File {
        require(Regex("[a-f0-9]{64}").matches(id)) { "无效的本机制图任务" }
        val dir = File(root, id)
        require(!Files.isSymbolicLink(dir.toPath()) && dir.canonicalFile == dir.absoluteFile)
        return dir
    }
    fun read(id: String): QwenImageJob? = withStoreLock {
        val file = File(directory(id), "job.json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return@withStoreLock null
        val raw = AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && output.size() + count <= 65536) { "制图任务记录无效或过大" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= 65536) { "制图任务记录过大" }
            bytes.toString(Charsets.UTF_8)
        }
        parse(JSONObject(raw)).also { require(it.id == id) }
    }
    fun write(job: QwenImageJob) = withStoreLock {
        validate(job)
        val dir = directory(job.id).apply { check(mkdirs() || isDirectory) }
        val file = AtomicFile(File(dir, "job.json"))
        val output = file.startWrite()
        try { output.write(json(job).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        if (job.state in setOf("decoded", "approved", "rejected", "review_unknown")) {
            val receipt = AtomicFile(File(dir, "attempt-${job.attempt}.json"))
            val stream = receipt.startWrite()
            try { stream.write(json(job).toString().toByteArray()); receipt.finishWrite(stream) }
            catch (failure: Throwable) { receipt.failWrite(stream); throw failure }
        }
    }
    fun registeredOrUnavailable(id: String): QwenImageJob? = try { read(id) }
    catch (failure: Exception) {
        android.util.Log.w("ZhiYuQwenJobs", "Image reservation unreadable; retained without repeating a paid request: ${failure.javaClass.simpleName}")
        throw com.zhiyu.app.data.LocalSceneBackgroundUnavailable("unknown")
    }
    fun phaseDirectory(job: QwenImageJob): File = File(directory(job.id), "phases/${UUID.randomUUID()}")
        .apply { check(mkdirs()) }
    fun file(job: QwenImageJob, relative: String): File {
        require(Regex("(?:phases/[a-f0-9-]{36}/[a-z0-9_.-]+|decoded-[01]\\.png|composite-[a-f0-9]{64}\\.png)").matches(relative))
        val path = File(directory(job.id), relative)
        require(path.canonicalFile == path.absoluteFile && !Files.isSymbolicLink(path.toPath()))
        return path
    }
    fun relative(job: QwenImageJob, file: File): String {
        require(file.canonicalFile == file.absoluteFile && file.toPath().startsWith(directory(job.id).toPath()))
        return file.relativeTo(directory(job.id)).invariantSeparatorsPath.also { this.file(job, it) }
    }
    fun pending(): List<QwenImageJob> = withStoreLock {
        root.listFiles().orEmpty().filter { Regex("[a-f0-9]{64}").matches(it.name) }.mapNotNull { retainedRead(it.name) }
            .filter { it.state in setOf("prepared", "encoded", "sampled", "decoded") }
            .sortedWith(compareBy<QwenImageJob> {
                val tomorrow = LocalDate.parse(com.zhiyu.app.domain.LocalDailyRules.dayId(java.time.Instant.now())).plusDays(1).toString()
                if (it.day == tomorrow) 0 else 1
            }.thenBy { it.day }.thenBy { it.slot })
    }
    fun sweepBefore(today: String) = withStoreLock {
        require(LocalDate.parse(today).toString() == today)
        root.listFiles().orEmpty().filter { Regex("[a-f0-9]{64}").matches(it.name) }.forEach { dir ->
            val job = retainedRead(dir.name) ?: return@forEach
            if (job.day >= today || job.state !in setOf("approved", "rejected", "review_unknown", "prompt_unknown", "stale")) return@forEach
            val phases = File(directory(job.id), "phases")
            if (phases.exists()) {
                require(!Files.isSymbolicLink(phases.toPath()) && phases.canonicalFile == phases.absoluteFile)
                Files.walk(phases.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    require(path.startsWith(phases.toPath())); Files.deleteIfExists(path)
                } }
            }
        }
    }
    private fun retainedRead(id: String): QwenImageJob? = try { read(id) }
    catch (failure: Exception) {
        // A corrupt record is retained for inspection, never recreated (its paid-call status is unknown).
        android.util.Log.w("ZhiYuQwenJobs", "Unreadable retained image task excluded: ${failure.javaClass.simpleName}")
        null
    }
    /** AtomicFile.openRead may recover .bak, so readers also take this cross-process exclusive lock. */
    private fun <T> withStoreLock(block: () -> T): T = synchronized(lock) {
        val key = root.absolutePath
        val owned = requireNotNull(held.get())
        if (key in owned) return@synchronized block()
        val file = File(root, ".store.lock")
        require(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()))
        RandomAccessFile(file, "rw").use { access -> access.channel.use { channel -> channel.lock().use {
            owned.add(key)
            try { block() } finally { owned.remove(key) }
        } } }
    }
    private fun validate(job: QwenImageJob) {
        directory(job.id); MediaRules.path(job.chat)
        require(LocalDate.parse(job.day).toString() == job.day && job.version >= 0 && job.attempt in 0..1)
        require(job.seed in 0..Int.MAX_VALUE.toLong() && job.tokens in 0..498 && job.prompt.toByteArray().size <= 4000)
        require(job.state in setOf("prompt_reserved", "prompt_unknown", "prepared", "encoded", "sampled", "decoded", "approved", "rejected", "review_unknown", "stale"))
        require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(job.slot))
        require(job.activity.length in 1..200 && job.tags.size <= 16)
        require(job.failures in 0..3)
        listOfNotNull(job.condition, job.checkpoint, job.decoded).forEach { file(job, it) }
        job.decodedSha?.let { require(Regex("[a-f0-9]{64}").matches(it)) }
    }
    private fun json(j: QwenImageJob) = JSONObject().put("id",j.id).put("chat",j.chat).put("day",j.day).put("version",j.version)
        .put("reference_id",j.referenceId).put("reference_sha",j.referenceSha).put("scene",j.scene).put("slot",j.slot)
        .put("activity",j.activity).put("tags",org.json.JSONArray(j.tags)).put("seed",j.seed).put("state",j.state)
        .put("prompt",j.prompt).put("attempt",j.attempt).put("tokens",j.tokens).put("condition",j.condition)
        .put("checkpoint",j.checkpoint).put("decoded",j.decoded).put("decoded_sha",j.decodedSha).put("pack_sha",QwenRelease.MANIFEST_SHA)
        .put("native_failures",j.failures)
    private fun parse(j: JSONObject): QwenImageJob {
        require(j.getString("pack_sha") == QwenRelease.MANIFEST_SHA)
        val tags = j.getJSONArray("tags")
        fun nullable(key: String) = if (j.isNull(key)) null else j.getString(key)
        return QwenImageJob(j.getString("id"),j.getString("chat"),j.getString("day"),j.getInt("version"),
            nullable("reference_id"),nullable("reference_sha"),j.getString("scene"),j.getString("slot"),
            j.getString("activity"),(0 until tags.length()).map(tags::getString),j.getLong("seed"),j.getString("state"),
            j.getString("prompt"),j.getInt("attempt"),j.getInt("tokens"),nullable("condition"),nullable("checkpoint"),
            nullable("decoded"),nullable("decoded_sha"),j.optInt("native_failures",0)).also(::validate)
    }
    companion object {
        private val lock = Any()
        private val held = ThreadLocal.withInitial { mutableSetOf<String>() }
    }
}
