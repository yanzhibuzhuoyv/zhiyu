package com.zhiyu.app.data

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.domain.MediaRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StickerRetirementResult(val removedRecords: Int = 0, val removedBlobs: Int = 0)

/** Retires the reusable library only; already-sent original message media remains downloadable. */
class StickerLibraryRetirement(context: Context, private val pinnedIds: () -> Set<String> = {
    LocalChatDatabase(context.applicationContext).use { it.pinnedMediaIds() }
}) {
    private val anchor = context.applicationContext.filesDir.canonicalFile
    private val root = File(anchor, "local_media")
    private val folder = File(root, "sticker")
    private val journal = File(root, "sticker_retirement_v2.json")
    private val store = LocalMediaStore(context.applicationContext)
    private data class Target(val id: String, val sha: String)

    fun retire(): StickerRetirementResult = synchronized(LocalMediaStore.lock) {
        val pins = pinnedIds().toSet().also { ids -> ids.forEach { MediaRules.path(it); store.details(it) } }
        val previous = readIntent()
        val records = scan()
        val current = records.associateBy { it.attachment.id }
        previous.forEach { row -> current[row.id]?.let { require(it.attachment.sha256 == row.sha) { "表情退役恢复标注不一致" } } }
        val targets = (previous + records.map { Target(it.attachment.id, it.attachment.sha256) })
            .filter { it.id !in pins }.distinctBy { it.id }
        require(targets.size <= MAX_RECORDS) { "表情退役记录过多" }
        val protectedBlobs = records.filter { it.attachment.id in pins }.map { it.attachment.sha256 }.toSet()
        // Resolve and validate every prospective path before the first mutation.
        targets.forEach { row ->
            listOf(metadata(row), blob(row.sha)).forEach { file ->
                safe(file); require(!file.exists() || file.isFile) { "表情退役目标不是普通文件" }
            }
        }
        writeIntent(targets)
        var removedRecords = 0
        targets.forEach { row ->
            val file = metadata(row)
            if (file.exists()) { check(file.isFile && file.delete()) { "旧表情标注暂时无法清理" }; removedRecords++ }
        }
        var removedBlobs = 0
        targets.map { it.sha }.distinct().filterNot(protectedBlobs::contains).forEach { sha ->
            val file = blob(sha)
            if (file.exists()) { check(file.isFile && file.delete()) { "旧表情图片暂时无法清理" }; removedBlobs++ }
        }
        writeIntent(emptyList())
        StickerRetirementResult(removedRecords, removedBlobs)
    }

    private fun scan(): List<LocalMediaDetails> {
        safe(folder)
        if (!folder.exists()) return emptyList()
        check(folder.isDirectory) { "旧表情目录无效" }
        val children = checkNotNull(folder.listFiles()) { "旧表情目录无法读取" }
        require(children.none { it.name.endsWith(".json.bak") || it.name.endsWith(".json.new") }) { "旧表情标注写入未完成" }
        val files = children.filter { it.extension == "json" }
        require(files.size <= MAX_RECORDS) { "旧表情记录过多" }
        return files.map { file ->
            safe(file); require(file.isFile) { "旧表情标注无效" }
            MediaRules.path(file.nameWithoutExtension)
            store.details(file.nameWithoutExtension).also {
                require(it.attachment.kind == "sticker" && it.attachment.id == file.nameWithoutExtension) { "旧表情标注归属无效" }
            }
        }
    }

    private fun readIntent(): List<Target> {
        safe(journal); safe(File(journal.path + ".bak")); safe(File(journal.path + ".new"))
        if (!journal.exists() && !File(journal.path + ".bak").exists()) return emptyList()
        val raw = AtomicFile(journal).openRead().use { stream ->
            require(stream.channel.size() in 1..MAX_JOURNAL_BYTES) { "表情退役恢复文件过大" }
            stream.readBytes()
        }
        val data = JSONObject(raw.toString(Charsets.UTF_8))
        require(data.getInt("schema") == 2) { "表情退役恢复版本无效" }
        val pending = data.getJSONArray("pending")
        require(pending.length() <= MAX_RECORDS) { "表情退役恢复记录过多" }
        val rows = List(pending.length()) { index ->
            val row = pending.getJSONObject(index)
            Target(row.getString("id"), row.getString("sha256")).also {
                MediaRules.path(it.id); require(Regex("[a-f0-9]{64}").matches(it.sha)) { "表情退役摘要无效" }
            }
        }
        require(rows.map { it.id }.toSet().size == rows.size) { "表情退役恢复编号重复" }
        return rows
    }

    private fun writeIntent(rows: List<Target>) {
        safe(journal)
        val pending = JSONArray().also { array -> rows.forEach { array.put(JSONObject().put("id", it.id).put("sha256", it.sha)) } }
        val bytes = JSONObject().put("schema", 2).put("pending", pending).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JOURNAL_BYTES)
        LocalMediaStore.write(journal, bytes)
    }
    private fun metadata(row: Target) = File(folder, "${row.id}.json")
    private fun blob(sha: String) = File(File(folder, "blobs"), "$sha.bin")
    private fun safe(file: File) {
        val normalized = file.absoluteFile.normalize()
        require(root.canonicalFile == root.absoluteFile.normalize() && root.parentFile == anchor) { "表情退役根目录越界" }
        require(normalized == root || normalized.path.startsWith(root.path + File.separator)) { "表情退役路径越界" }
        require(file.canonicalFile == normalized) { "表情退役路径不能指向其他位置" }
    }
    companion object { private const val MAX_RECORDS = 20_000; private const val MAX_JOURNAL_BYTES = 4 * 1024 * 1024L }
}
