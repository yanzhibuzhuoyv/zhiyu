package com.zhiyu.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.zhiyu.app.domain.MediaAttachment
import com.zhiyu.app.domain.MediaRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class LocalMediaDetails(val attachment: MediaAttachment, val description: String,
    val tags: List<String>, val purpose: String)

/** One private phone owner. Asset URLs are compatibility labels, never network requests. */
class LocalMediaStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "local_media")
    companion object {
        private const val MAX_METADATA_BYTES = 16 * 1024L
        private const val MAX_RECORDS = 20_000
        internal val lock = Any()
        internal fun identifier() = UUID.randomUUID().toString().replace("-", "")
        internal fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        internal fun write(file: File, bytes: ByteArray) {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建本地素材目录" }
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
    fun ingest(kind: String, bytes: ByteArray, sourceLabel: String = "本地导入", visualVersion: Int = 0,
        dayId: String? = null, description: String = "", tags: List<String> = emptyList(),
        purpose: String = "library"): MediaAttachment = synchronized(lock) {
        require(kind in setOf("image", "sticker") && purpose in setOf("library", "portrait", "chat", "scene_background")) { "素材分类无效" }
        require(visualVersion >= 0 && sourceLabel.length <= 200 && description.length <= 1000 &&
            tags.size <= 30 && tags.all { it.isNotBlank() && it.length <= 40 } &&
            (dayId == null || Regex("\\d{4}-\\d{2}-\\d{2}").matches(dayId))) { "素材标注无效" }
        val mime = validateImage(bytes)
        val hash = sha(bytes)
        val folder = File(root, kind)
        val records = metadataFiles(kind)
        // A chat attachment identifies an intentional selection, not the underlying pixel blob.
        // Fresh selections share the immutable SHA blob but must not revive a previously sent ID.
        val existing = if (purpose == "chat") null else records.asSequence().map { readRecord(it) }
            .firstOrNull { it.attachment.sha256 == hash && it.attachment.visualVersion == visualVersion && it.purpose == purpose &&
                it.attachment.dayId == dayId && it.tags.toSet() == tags.toSet() }
        if (existing != null) { download(existing.attachment.id, hash); return@synchronized existing.attachment }
        require(records.size < MAX_RECORDS) { "本地素材过多，请先整理素材库" }
        val id = identifier()
        val asset = MediaRules.check(MediaAttachment(id, kind, MediaRules.path(id), mime, hash,
            dayId, visualVersion, sourceLabel))
        val blob = File(File(folder, "blobs"), "$hash.bin")
        if (blob.isFile) require(blob.length() == bytes.size.toLong() && sha(blob.readBytes()) == hash) { "本地素材文件损坏" }
        else write(blob, bytes)
        val json = JSONObject().put("id", id).put("kind", kind).put("mime", mime)
            .put("sha256", asset.sha256).put("version", visualVersion).put("day", dayId)
            .put("source", sourceLabel).put("description", description).put("tags", JSONArray(tags))
            .put("purpose", purpose)
        write(File(folder, "$id.json"), json.toString().toByteArray(Charsets.UTF_8))
        asset
    }
    fun list(kind: String, visualVersion: Int? = null, limit: Int = 500, dayId: String? = null): List<MediaAttachment> = synchronized(lock) {
        require(kind in setOf("image", "sticker") && (visualVersion == null || visualVersion >= 0) && limit in 1..1000) { "素材查询条件无效" }
        require(dayId==null || Regex("\\d{4}-\\d{2}-\\d{2}").matches(dayId)) { "素材日期无效" }
        metadataFiles(kind).asSequence().sortedBy { it.name }.map { readRecord(it) }
            .filter { it.purpose == "library" && (visualVersion == null || it.attachment.visualVersion == visualVersion) &&
                (dayId==null || it.attachment.dayId==null || it.attachment.dayId==dayId) }
            .take(limit).map { it.attachment }.toList()
    }
    fun details(id: String): LocalMediaDetails = synchronized(lock) {
        MediaRules.path(id)
        val file = listOf("image", "sticker").map { File(File(root, it), "$id.json") }.firstOrNull { it.isFile }
            ?: throw IllegalArgumentException("本机没有这张素材")
        readRecord(file)
    }
    fun download(id: String, expectedSha: String? = null): ByteArray = synchronized(lock) {
        val asset = details(id).attachment
        if (expectedSha != null) require(expectedSha == asset.sha256) { "素材已变更，请重新打开" }
        val file = File(File(File(root, asset.kind), "blobs"), "${asset.sha256}.bin")
        require(file.isFile && file.length() in 1..MediaRules.MAX_BYTES.toLong()) { "本地素材缺失或过大" }
        file.readBytes().also { require(sha(it) == asset.sha256) { "本地素材校验失败" } }
    }
    private fun parse(value: JSONObject): LocalMediaDetails {
        val id = value.getString("id")
        val asset = MediaRules.check(MediaAttachment(id, value.getString("kind"), MediaRules.path(id),
            value.getString("mime"), value.getString("sha256"),
            if (value.isNull("day")) null else value.getString("day"), value.getInt("version"), value.getString("source")))
        val tags = value.getJSONArray("tags")
        val description = value.getString("description")
        val purpose = value.getString("purpose")
        require(description.length <= 1000 && tags.length() <= 30 && purpose in setOf("library", "portrait", "chat", "scene_background")) { "本地素材标注损坏" }
        val labels = List(tags.length()) { tags.getString(it) }
        require(labels.all { it.isNotBlank() && it.length <= 40 }) { "本地素材标签损坏" }
        return LocalMediaDetails(asset, description, labels, purpose)
    }
    private fun readRecord(file: File): LocalMediaDetails {
        require(file.length() in 1..MAX_METADATA_BYTES) { "本地素材标注过大或缺失" }
        val result = parse(JSONObject(file.readText(Charsets.UTF_8)))
        require(result.attachment.id == file.nameWithoutExtension && result.attachment.kind == file.parentFile!!.name) { "本地素材归属损坏" }
        return result
    }
    private fun metadataFiles(kind: String): List<File> {
        val files = File(root, kind).listFiles { file -> file.extension == "json" }?.toList().orEmpty()
        require(files.size <= MAX_RECORDS) { "本地素材过多，请先整理素材库" }
        return files
    }
    private fun validateImage(bytes: ByteArray): String {
        require(bytes.size in 1..MediaRules.MAX_BYTES) { "素材须为 5 MB 以内的图片" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192 &&
            options.outWidth.toLong() * options.outHeight <= 20_000_000) { "图片须小于 2000 万像素，单边不超过 8192" }
        val mime = options.outMimeType
        require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "请选择 PNG、JPEG、WebP 或 GIF 图片" }
        return mime
    }
}
