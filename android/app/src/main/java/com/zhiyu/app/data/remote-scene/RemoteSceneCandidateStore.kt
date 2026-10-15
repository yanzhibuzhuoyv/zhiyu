package com.zhiyu.app.data.remotescene

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.data.AndroidRemoteImageBounds
import com.zhiyu.app.domain.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** Private candidates never enter LocalMediaStore or assistant media selection until reviewed. */
class RemoteSceneCandidateStore(context: Context, directory: String = "remote_scene_candidates_v1") {
    private val filesRoot = context.applicationContext.filesDir.canonicalFile
    private val root: File
    init {
        require(Regex("remote_scene_[A-Za-z0-9_]{1,80}").matches(directory))
        root = File(filesRoot, directory).absoluteFile
        checkRoot()
    }
    fun clearPixels(chat: String, id: String, expectedSha: String): RemoteSceneCandidate = synchronized(lock) {
        MediaRules.path(chat); ChatPhotoRules.digest(expectedSha)
        // Ownership checks cannot invoke AtomicFile's read recovery, which may rename files on disk.
        val old = requireNotNull(readRecord(id, recover = false)) { "生活照候选不存在" }
        require(old.chat == chat && old.imageSha == expectedSha) { "生活照候选归属或像素已变化" }
        val pixels = family(path(id, "bin"))
        val metadata = path(id, "json"); family(metadata)
        val removed = old.copy(pixelsRemoved = true)
        if (!old.pixelsRemoved) atomic(metadata, json(removed))
        // The durable tombstone is committed before any pixel deletion. A failed delete remains safely retryable.
        pixels.forEach { check(!it.exists() || it.delete()) { "候选像素清理未完成，可再次清理" } }
        removed
    }
    /** The caller already holds its media lock. Only local publication belongs in this block, never network or decoding. */
    fun <T> withCurrentCandidate(candidate: RemoteSceneCandidate, block: () -> T): T = synchronized(lock) {
        validate(candidate)
        require(!candidate.pixelsRemoved && readRecord(candidate.requestId) == candidate) { "候选已清理或复核状态已更新" }
        block()
    }
    fun read(requestId: String): RemoteSceneCandidate? = synchronized(lock) {
        readRecord(requestId)
    }
    fun hasCapacity():Boolean=synchronized(lock) {
        try {
            checkRoot()
            val reserve=Image2Protocol.MAX_IMAGE_BYTES.toLong()+2L*MAX_METADATA
            val usage=if(root.exists())diskUsage()else 0 to 0L
            usage.first<=2046&&MAX_DISK-usage.second>=reserve&&filesRoot.usableSpace>=reserve
        } catch(_:IllegalArgumentException){false}
          catch(_:IllegalStateException){false}
          catch(_:java.io.IOException){false}
    }
    private fun readRecord(requestId: String, recover: Boolean = true): RemoteSceneCandidate? {
        val file = path(requestId, "json")
        val artifacts = family(file)
        if (!file.exists() && !artifacts[1].exists()) return null
        val input = if (recover) AtomicFile(file).openRead() else
            (if (artifacts[1].exists()) artifacts[1] else file).inputStream()
        val raw = input.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer); if (count < 0) break
                require(count > 0 && output.size() + count <= MAX_METADATA) { "生活照候选标注过大" }
                output.write(buffer, 0, count)
            }
            LocalMemoryCodec.utf8(output.toByteArray())
        }
        val map = AgnesImageJson.parse(raw) as? Map<*, *> ?: throw IllegalArgumentException("生活照候选标注损坏")
        require(map.keys.containsAll(requiredFields) && map.keys.all { it in allowedFields }) { "生活照候选字段无效" }
        fun string(key: String) = map[key] as? String ?: throw IllegalArgumentException("生活照候选标注损坏")
        fun optionalString(key: String) = map[key]?.let { it as? String ?: throw IllegalArgumentException("生活照候选字段类型无效") }
        val attempt = map["attempt"] as? Double ?: throw IllegalArgumentException("生活照候选次数无效")
        require(attempt in 0.0..1.0 && attempt == attempt.toInt().toDouble())
        val value = RemoteSceneCandidate(string("requestId"), string("chat"), string("bindingSha"), string("imageSha"),
            string("mime"), string("prompt"), attempt.toInt(), string("day"), string("slot"), string("trajectory"),
            LifePhotoReviewStatus.entries.firstOrNull { it.name == string("status") } ?: throw IllegalArgumentException("生活照候选状态无效"),
            string("reason"), optionalString("correction"), optionalString("originalId"), optionalString("originalSha"),
            map["visualVersion"]?.let { require(it is Double && it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()); it.toInt() },
            optionalString("packageSha"),
            if (!map.containsKey("pixelsRemoved")) false else map["pixelsRemoved"] as? Boolean ?: throw IllegalArgumentException("生活照像素清理状态无效"),
            optionalString("retryOf"), optionalString("reviewBindingSha"))
        validate(value); require(value.requestId == requestId) { "生活照候选归属无效" }; return value
    }
    fun write(candidate: RemoteSceneCandidate, bytes: ByteArray): Unit = synchronized(lock) {
        validate(candidate)
        require(!candidate.pixelsRemoved) { "已清理候选不能重新写入像素" }
        val old = readRecord(candidate.requestId)
        require(old == null || !old.pixelsRemoved) { "已清理候选不能复活" }
        require(old == null || old.copy(status = candidate.status, reason = candidate.reason,
            correction = candidate.correction, reviewBindingSha = candidate.reviewBindingSha) == candidate) { "已保存的候选原图或来源不能改写" }
        require(ChatPhotoRules.sha(bytes) == candidate.imageSha) { "生活照候选像素校验失败" }
        AndroidRemoteImageBounds.validate(bytes, candidate.mime)
        val encoded = json(candidate)
        require(encoded.size <= MAX_METADATA)
        checkRoot()
        check(root.isDirectory || root.mkdirs()) { "无法创建生活照候选目录" }
        val usage = diskUsage()
        require(old != null || usage.first <= 2046) { "生活照候选过多，请先整理候选" }
        val pixelFile = path(candidate.requestId, "bin")
        val metadataFile = path(candidate.requestId, "json")
        val pixelArtifacts = family(pixelFile); family(metadataFile)
        require(usage.second + (if (pixelFile.exists() || pixelArtifacts[1].exists()) 0 else bytes.size) + encoded.size <= MAX_DISK) {
            "生活照候选超过本机128MiB保存预算"
        }
        if (pixelFile.exists() || pixelArtifacts[1].exists()) {
            require(ChatPhotoRules.sha(binary(pixelFile)) == candidate.imageSha) { "候选原图损坏" }
        } else atomic(pixelFile, bytes)
        atomic(metadataFile, encoded)
    }
    fun bytes(candidate: RemoteSceneCandidate): ByteArray = synchronized(lock) {
        validate(candidate)
        require(!candidate.pixelsRemoved) { "生活照候选像素已清理" }
        val saved = requireNotNull(readRecord(candidate.requestId)) { "生活照候选不存在" }
        require(!saved.pixelsRemoved) { "生活照候选像素已清理" }
        require(saved == candidate) { "生活照候选已更新" }
        val file = path(candidate.requestId, "bin")
        val artifacts = family(file)
        require(file.exists() || artifacts[1].exists()) { "生活照候选原图缺失" }
        val result = binary(file)
        require(ChatPhotoRules.sha(result) == candidate.imageSha) { "生活照候选原图校验失败" }
        AndroidRemoteImageBounds.validate(result, candidate.mime); result
    }
    private fun binary(file: File): ByteArray {
        family(file)
        return AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0 && output.size().toLong() + count <= Image2Protocol.MAX_IMAGE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    private fun path(id: String, extension: String): File {
        MediaRules.path(id)
        checkRoot()
        return checkedFile(File(root, "$id.$extension"))
    }
    private fun checkRoot() {
        require(root.parentFile == filesRoot && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile == root &&
            (!root.exists() || root.isDirectory)) { "生活照候选目录无效" }
    }
    private fun checkedFile(file: File): File {
        require(file.parentFile == root && !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file &&
            (!file.exists() || file.isFile)) { "生活照候选文件路径无效" }
        return file
    }
    private fun family(file: File): List<File> = listOf(file, File(file.path + ".bak"), File(file.path + ".new")).map(::checkedFile)
    private fun diskUsage(): Pair<Int, Long> {
        var count = 0; var size = 0L
        Files.newDirectoryStream(root.toPath()).use { entries -> entries.forEach { entry ->
            val file = checkedFile(entry.toFile())
            require(++count <= 2048) { "生活照候选过多，请先整理候选" }
            val length = file.length()
            require(length in 0..MAX_DISK && size + length <= MAX_DISK) { "生活照候选超过本机128MiB保存预算" }
            size += length
        } }
        return count to size
    }
    private fun validate(value: RemoteSceneCandidate) {
        MediaRules.path(value.requestId); MediaRules.path(value.chat)
        listOf(value.bindingSha, value.imageSha, value.trajectory).forEach(ChatPhotoRules::digest)
        require(value.mime in setOf("image/png", "image/jpeg") && value.attempt in 0..1 &&
            value.prompt.isNotBlank() && value.prompt.length <= 4000 && value.reason.isNotBlank() && value.reason.length <= 240)
        require(value.day == LocalDate.parse(value.day).toString() && Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value.slot))
        require(value.correction == null || RemoteSceneRules.correction(value.correction) != null)
        require(value.status != LifePhotoReviewStatus.APPROVED || value.correction == null)
        require((value.originalId == null) == (value.originalSha == null))
        value.originalId?.let(MediaRules::path); value.originalSha?.let(ChatPhotoRules::digest)
        value.packageSha?.let(ChatPhotoRules::digest); require(value.visualVersion == null || value.visualVersion >= 0)
        value.retryOf?.let { MediaRules.path(it); require(it != value.requestId) { "生活照候选不能重试自己" } }
        value.reviewBindingSha?.let(ChatPhotoRules::digest)
        LocalMemoryCodec.bytes(value.prompt); LocalMemoryCodec.bytes(value.reason)
    }
    private fun json(v: RemoteSceneCandidate): ByteArray = LocalMemoryCodec.bytes(JSONObject().put("requestId", v.requestId)
        .put("chat", v.chat).put("bindingSha", v.bindingSha).put("imageSha", v.imageSha).put("mime", v.mime).put("prompt", v.prompt)
        .put("attempt", v.attempt).put("day", v.day).put("slot", v.slot).put("trajectory", v.trajectory)
        .put("status", v.status.name).put("reason", v.reason).put("correction", v.correction ?: JSONObject.NULL)
        .put("originalId", v.originalId ?: JSONObject.NULL).put("originalSha", v.originalSha ?: JSONObject.NULL)
        .put("visualVersion", v.visualVersion ?: JSONObject.NULL).put("packageSha", v.packageSha ?: JSONObject.NULL)
        .put("pixelsRemoved", v.pixelsRemoved).put("retryOf", v.retryOf ?: JSONObject.NULL)
        .put("reviewBindingSha", v.reviewBindingSha ?: JSONObject.NULL).toString())
    private fun atomic(file: File, bytes: ByteArray) {
        family(file)
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }
    companion object {
        private val lock = Any(); private const val MAX_METADATA = 32 * 1024; private const val MAX_DISK = 128L * 1024 * 1024
        private val requiredFields = setOf("requestId", "chat", "bindingSha", "imageSha", "mime", "prompt", "attempt", "day", "slot", "trajectory", "status", "reason")
        private val allowedFields = requiredFields + setOf("correction", "originalId", "originalSha", "visualVersion", "packageSha", "pixelsRemoved", "retryOf", "reviewBindingSha")
    }
}
