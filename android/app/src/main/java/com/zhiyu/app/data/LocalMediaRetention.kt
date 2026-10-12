package com.zhiyu.app.data

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LocalMediaRetentionFailure(val phase: String, val id: String?, val sha256: String?, val message: String)
data class LocalMediaRetentionResult(val examinedRecords: Int = 0, val eligibleRecords: Int = 0,
    val removedMetadata: Int = 0, val removedBlobs: Int = 0, val freedBytes: Long = 0,
    val pendingRecords: Int = 0, val failures: List<LocalMediaRetentionFailure> = emptyList(), val auditSaved: Boolean = false)

/**
 * Audits existing final images under the ingest / attachment-commit lock. Final decoded images
 * and metadata are retained for later conversation/reproduction. A historical deletion journal
 * is validated and retired without executing its old targets. Intermediate cleanup belongs only
 * to the separate Qwen job store; no LocalMediaStore image or original OC is deleted here.
 */
class LocalMediaRetention(context: Context, private val pinnedIds: () -> Set<String>,
    @Suppress("UNUSED_PARAMETER") deleteFile: (File) -> Boolean = { false }) {
    private val filesAnchor = context.applicationContext.filesDir.canonicalFile
    private val root = File(filesAnchor, "local_media")
    private val store = LocalMediaStore(context.applicationContext)
    private val journal = File(root, "retention_v1.json")
    private data class Target(val record: MediaRetentionRecord) {
        val id get() = record.id
        val blob get() = MediaRetentionBlob(record.kind, record.sha256)
    }

    fun sweep(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))): LocalMediaRetentionResult =
        synchronized(LocalMediaStore.lock) { sweepLocked(today) }

    private fun sweepLocked(today: LocalDate): LocalMediaRetentionResult {
        val previous = try { readIntent() } catch (error: Exception) {
            return failure("journal", error) // Never overwrite a damaged recovery intent.
        }
        val pins = try { pinnedIds().toSet().also { ids -> ids.forEach { MediaRules.path(it) } } }
        catch (error: Exception) { return save(previous, failure("pins", error)) }
        val records = try { scan() } catch (error: Exception) { return save(previous, failure("metadata", error)) }
        val plan = LocalMediaRetentionRules.plan(records, pins, today)
        check(plan.removeIds.isEmpty() && plan.deleteBlobs.isEmpty()) { "已解码图片不能进入回收计划" }
        return save(emptyList(), LocalMediaRetentionResult(examinedRecords = records.size))
    }

    /** Complete scan, no list(limit=1000) shortcut. Any unreadable metadata aborts before deletion. */
    private fun scan(): List<MediaRetentionRecord> {
        requireInsideRoot(root)
        val files = listOf("image", "sticker").flatMap { kind ->
            val directory = File(root, kind)
            if (!directory.exists()) emptyList() else {
                requireInsideRoot(directory); check(directory.isDirectory) { "素材目录无效" }
                val all = checkNotNull(directory.listFiles()) { "无法读取素材目录" }
                check(all.none { it.name.endsWith(".json.bak") || it.name.endsWith(".json.new") }) { "素材写入尚未完成，暂不回收" }
                all.filter { it.extension == "json" }.onEach { requireInsideRoot(it); check(it.isFile) { "素材标注不是文件" } }
            }
        }
        require(files.size <= LocalMediaRetentionRules.MAX_RECORDS) { "素材回收扫描超出上限" }
        val records = files.map { file ->
            MediaRules.path(file.nameWithoutExtension)
            val detail = store.details(file.nameWithoutExtension)
            require(detail.attachment.kind == file.parentFile!!.name) { "素材编号归属不一致" }
            val asset = detail.attachment
            LocalMediaRetentionRules.check(MediaRetentionRecord(asset.id, asset.kind, asset.sha256, asset.dayId,
                detail.purpose, detail.tags.toSet(), asset.sourceLabel))
        }
        require(records.map { it.id }.toSet().size == records.size) { "素材编号重复" }
        return records
    }

    private fun readIntent(): List<Target> {
        requireInsideRoot(journal)
        listOf(".bak", ".new").forEach { requireInsideRoot(File(journal.path + it)) }
        val atomic = AtomicFile(journal)
        if (!journal.exists() && !File(journal.path + ".bak").exists()) return emptyList()
        val bytes = atomic.openRead().use { input ->
            require(input.channel.size() in 1..1024 * 1024L) { "素材回收日志过大或缺失" }
            input.readBytes()
        }
        require(bytes.size in 1..1024 * 1024) { "素材回收日志过大" }
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getInt("schema") == 1) { "素材回收日志版本无效" }
        val pending = value.getJSONArray("pending")
        require(pending.length() <= LocalMediaRetentionRules.MAX_BATCH) { "素材回收日志超出上限" }
        val result = List(pending.length()) { index ->
            val target = pending.getJSONObject(index); val id = target.getString("id"); MediaRules.path(id)
            val blob = LocalMediaRetentionRules.checkBlob(MediaRetentionBlob(target.getString("kind"), target.getString("sha256")))
            require(blob.kind == "image") { "回收日志不能包含原始表情素材" }
            val tags = target.getJSONArray("tags")
            require(tags.length() <= 30) { "素材回收标签过多" }
            val record = LocalMediaRetentionRules.check(MediaRetentionRecord(id, blob.kind, blob.sha256,
                target.getString("day"), target.getString("purpose"), List(tags.length()) { tags.getString(it) }.toSet(),
                target.getString("source")))
            LocalDate.parse(record.dayId!!)
            val historicalCache = record.purpose == "scene_background" && record.sourceLabel == "生成的空场景" ||
                record.purpose == "library" && record.sourceLabel == "恋人的生活画面" && "daily_stock" in record.tags
            require(historicalCache) { "历史回收日志包含非缓存素材" }
            Target(record)
        }
        require(result.map { it.id }.toSet().size == result.size) { "素材回收日志编号重复" }
        return result
    }

    private fun save(pending: List<Target>, result: LocalMediaRetentionResult): LocalMediaRetentionResult = try {
        requireInsideRoot(journal)
        val targets = JSONArray().also { array -> pending.forEach { target -> array.put(JSONObject()
            .put("id", target.id).put("kind", target.blob.kind).put("sha256", target.blob.sha256)
            .put("day", target.record.dayId).put("purpose", target.record.purpose).put("source", target.record.sourceLabel)
            .put("tags", JSONArray(target.record.tags.toList()))) } }
        val errors = JSONArray().also { array -> result.failures.forEach { error -> array.put(JSONObject()
            .put("phase", error.phase).put("id", error.id).put("sha256", error.sha256).put("message", error.message)) } }
        val receipt = JSONObject().put("examined", result.examinedRecords).put("eligible", result.eligibleRecords)
            .put("removed_metadata", result.removedMetadata).put("removed_blobs", result.removedBlobs)
            .put("freed_bytes", result.freedBytes).put("failures", errors)
        val value = JSONObject().put("schema", 1).put("checked_at", Instant.now().toString()).put("pending", targets).put("last_result", receipt)
        LocalMediaStore.write(journal, value.toString().toByteArray(Charsets.UTF_8))
        result.copy(pendingRecords = pending.size, auditSaved = true)
    } catch (error: Exception) {
        result.copy(pendingRecords = pending.size, failures = result.failures +
            LocalMediaRetentionFailure("journal_write", null, null, safeMessage(error)), auditSaved = false)
    }
    private fun failure(phase: String, error: Exception) = LocalMediaRetentionResult(
        failures = listOf(LocalMediaRetentionFailure(phase, null, null, safeMessage(error))))
    private fun safeMessage(error: Exception): String = when (error) {
        is java.io.IOException -> "素材文件操作失败，请稍后重试"
        is SecurityException -> "素材文件访问受限，请稍后重试"
        else -> error.message?.take(160) ?: "素材回收未完成"
    }
    private fun requireInsideRoot(file: File) {
        val expected = root.absoluteFile.normalize(); val candidate = file.absoluteFile.normalize()
        require(candidate == expected || candidate.path.startsWith(expected.path + File.separator)) { "素材路径越界" }
        val realRoot = root.canonicalFile; val real = file.canonicalFile
        require(realRoot.parentFile == filesAnchor && realRoot.name == "local_media") { "素材根目录越界" }
        require(real == realRoot || real.path.startsWith(realRoot.path + File.separator)) { "素材实际路径越界" }
        require(realRoot == expected) { "素材根目录不能指向其他位置" }
    }
}
