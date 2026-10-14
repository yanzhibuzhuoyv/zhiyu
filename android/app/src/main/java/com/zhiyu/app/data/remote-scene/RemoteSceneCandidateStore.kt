package com.zhiyu.app.data.remotescene

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.data.AndroidRemoteImageBounds
import com.zhiyu.app.domain.*
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Private candidates never enter LocalMediaStore or assistant media selection until reviewed. */
class RemoteSceneCandidateStore(context: Context, directory: String = "remote_scene_candidates_v1") {
    private val root: File
    init {
        require(Regex("remote_scene_[A-Za-z0-9_]{1,80}").matches(directory))
        root = File(context.applicationContext.filesDir, directory).canonicalFile
    }
    fun read(requestId: String): RemoteSceneCandidate? = synchronized(lock) {
        val file = path(requestId, "json")
        if (!file.exists() && !File(file.path + ".bak").exists()) return@synchronized null
        val raw = AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0 && output.size() + count <= MAX_METADATA) { "生活照候选标注过大" }
                output.write(buffer, 0, count)
            }
            LocalMemoryCodec.utf8(output.toByteArray())
        }
        val map = AgnesImageJson.parse(raw) as? Map<*, *> ?: throw IllegalArgumentException("生活照候选标注损坏")
        fun string(key: String) = map[key] as? String ?: throw IllegalArgumentException("生活照候选标注损坏")
        val attempt = map["attempt"] as? Double ?: throw IllegalArgumentException("生活照候选次数无效")
        require(attempt in 0.0..1.0 && attempt == attempt.toInt().toDouble())
        val value = RemoteSceneCandidate(string("requestId"), string("chat"), string("bindingSha"), string("imageSha"),
            string("mime"), string("prompt"), attempt.toInt(), string("day"), string("slot"), string("trajectory"),
            LifePhotoReviewStatus.entries.firstOrNull { it.name == string("status") } ?: throw IllegalArgumentException("生活照候选状态无效"),
            string("reason"), map["correction"]?.let { it as? String ?: throw IllegalArgumentException("生活照候选修正无效") },
            map["originalId"]?.let { it as? String ?: throw IllegalArgumentException() },
            map["originalSha"]?.let { it as? String ?: throw IllegalArgumentException() },
            map["visualVersion"]?.let { require(it is Double && it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()); it.toInt() },
            map["packageSha"]?.let { it as? String ?: throw IllegalArgumentException() })
        validate(value); require(value.requestId == requestId) { "生活照候选归属无效" }; value
    }
    fun write(candidate: RemoteSceneCandidate, bytes: ByteArray): Unit = synchronized(lock) {
        validate(candidate)
        require(ChatPhotoRules.sha(bytes) == candidate.imageSha) { "生活照候选像素校验失败" }
        AndroidRemoteImageBounds.validate(bytes, candidate.mime)
        val old = read(candidate.requestId)
        require(old == null || old.copy(status = candidate.status, reason = candidate.reason,
            correction = candidate.correction) == candidate) { "已保存的候选原图或来源不能改写" }
        val encoded = json(candidate)
        require(encoded.size <= MAX_METADATA)
        check(root.isDirectory || root.mkdirs()) { "无法创建生活照候选目录" }
        val files = root.listFiles().orEmpty()
        require(files.size <= 2048 && (old != null || files.size <= 2046)) { "生活照候选过多，请先整理候选" }
        val pixelFile = path(candidate.requestId, "bin")
        val metadataFile = path(candidate.requestId, "json")
        require(files.sumOf { it.length() } + (if (pixelFile.exists()) 0 else bytes.size) + encoded.size <= MAX_DISK) {
            "生活照候选超过本机128MiB保存预算"
        }
        if (pixelFile.exists() || File(pixelFile.path + ".bak").exists()) {
            require(ChatPhotoRules.sha(binary(pixelFile)) == candidate.imageSha) { "候选原图损坏" }
        } else atomic(pixelFile, bytes)
        atomic(metadataFile, encoded)
    }
    fun bytes(candidate: RemoteSceneCandidate): ByteArray = synchronized(lock) {
        validate(candidate)
        val saved = requireNotNull(read(candidate.requestId)) { "生活照候选不存在" }
        require(saved == candidate) { "生活照候选已更新" }
        val file = path(candidate.requestId, "bin")
        require(file.exists() || File(file.path + ".bak").exists()) { "生活照候选原图缺失" }
        val result = binary(file)
        require(ChatPhotoRules.sha(result) == candidate.imageSha) { "生活照候选原图校验失败" }
        AndroidRemoteImageBounds.validate(result, candidate.mime); result
    }
    private fun binary(file: File): ByteArray = AtomicFile(file).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(count > 0 && output.size().toLong() + count <= Image2Protocol.MAX_IMAGE_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
    }
    private fun path(id: String, extension: String): File {
        MediaRules.path(id)
        return File(root, "$id.$extension").canonicalFile.also { require(it.parentFile == root) }
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
        LocalMemoryCodec.bytes(value.prompt); LocalMemoryCodec.bytes(value.reason)
    }
    private fun json(v: RemoteSceneCandidate): ByteArray = LocalMemoryCodec.bytes(JSONObject().put("requestId", v.requestId)
        .put("chat", v.chat).put("bindingSha", v.bindingSha).put("imageSha", v.imageSha).put("mime", v.mime).put("prompt", v.prompt)
        .put("attempt", v.attempt).put("day", v.day).put("slot", v.slot).put("trajectory", v.trajectory)
        .put("status", v.status.name).put("reason", v.reason).put("correction", v.correction ?: JSONObject.NULL)
        .put("originalId", v.originalId ?: JSONObject.NULL).put("originalSha", v.originalSha ?: JSONObject.NULL)
        .put("visualVersion", v.visualVersion ?: JSONObject.NULL).put("packageSha", v.packageSha ?: JSONObject.NULL).toString())
    private fun atomic(file: File, bytes: ByteArray) {
        val target = AtomicFile(file); val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }
    companion object { private val lock = Any(); private const val MAX_METADATA = 32 * 1024; private const val MAX_DISK = 128L * 1024 * 1024 }
}
