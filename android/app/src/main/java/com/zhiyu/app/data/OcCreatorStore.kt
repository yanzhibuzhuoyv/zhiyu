package com.zhiyu.app.data

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream

/** One atomic identity manifest per chat; old messages/media and provider binding are untouched. */
class OcCreatorStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "oc_identity_v1")
    fun read(chatId: String): OcIdentitySnapshot? = synchronized(lock) { readUnlocked(chatId) }
    /** A short local publication check; never hold this lock across model or image requests. */
    internal fun <T> withCurrentSnapshot(chatId:String,block:(OcIdentitySnapshot?)->T):T =
        synchronized(lock){block(readUnlocked(chatId))}
    fun beginPublication(chatId: String, expectedRevision: Int, expectedVersion: Int, identity: OcIdentity,
        pngSha: String, attempt: String): OcPendingPublication = synchronized(lock) {
        val before = readUnlocked(chatId)
        require((before?.identity?.revision ?: 0) == expectedRevision && identity.revision == expectedRevision + 1 &&
            (before == null || before.identity.ocId == identity.ocId) && identity.confirmed) { "角色已更新，请重新打开" }
        require(expectedVersion >= 0 && Regex("[a-f0-9]{64}").matches(pngSha) && attempt.isNotBlank())
        val key = path(chatId).absolutePath
        require(key !in activePublications) { "角色正在保存，请稍后再试" }
        OcIdentityRules.check(identity)
        val old = readPendingUnlocked(chatId)
        require(old == null || old.expectedVisualVersion == expectedVersion) { "上次外观保存尚未恢复，请重新打开" }
        val pending = OcPendingPublication(identity, expectedRevision, expectedVersion, pngSha, System.currentTimeMillis())
        val json = JSONObject().put("schema", 1).put("identity", encodeIdentity(identity)).put("identity_sha", OcIdentityRules.packageSha(identity))
            .put("base_revision", expectedRevision).put("expected_version", expectedVersion).put("png_sha", pngSha).put("created_at", pending.createdAt)
        writeAtomic(pendingPath(chatId), json.toString().toByteArray(Charsets.UTF_8))
        activePublications[key] = attempt
        pending
    }
    /**
     * Matching bytes recover a completed publish. Only an explicitly fresh profile version can
     * prove that a different, later profile superseded an intent; two-argument callers cannot abort.
     */
    fun recoverPublication(chatId: String, currentReference: OcAssetRef?,
        currentVisualVersion: Int? = null): OcIdentitySnapshot? = synchronized(lock) {
        require(currentVisualVersion == null || currentVisualVersion >= 0) { "当前外观版本无效" }
        currentReference?.let { ref ->
            MediaRules.path(ref.id)
            require(Regex("[a-f0-9]{64}").matches(ref.sha256) && ref.visualVersion >= 0 &&
                (currentVisualVersion == null || ref.visualVersion == currentVisualVersion)) { "当前外观快照不一致" }
        }
        val before = readUnlocked(chatId)
        if (path(chatId).absolutePath in activePublications) return before
        val pending = readPendingUnlocked(chatId) ?: return before
        val matches = currentReference != null && currentReference.sha256 == pending.pngSha &&
            currentReference.visualVersion.toLong() == pending.expectedVisualVersion.toLong() + 1
        if (!matches) {
            if (currentVisualVersion != null && currentVisualVersion > pending.expectedVisualVersion)
                archiveSuperseded(chatId, pending, currentReference, currentVisualVersion)
            return before
        }
        val reference = checkNotNull(currentReference)
        val result = if (before?.identity?.revision == pending.identity.revision && before.identity.reference == reference &&
            before.identity.ocId == pending.identity.ocId) before else commit(chatId, pending.expectedRevision, pending.identity.copy(reference = reference))
        AtomicFile(pendingPath(chatId)).delete()
        result
    }
    /** Write immutable audit first. Failed audit/deletion retains a retryable pending record. */
    private fun archiveSuperseded(chatId: String, pending: OcPendingPublication, currentReference: OcAssetRef?, version: Int) {
        val identitySha = OcIdentityRules.packageSha(pending.identity)
        val directory = File(File(root, "aborted"), chatId)
        check(directory.isDirectory || directory.mkdirs()) { "无法保存角色恢复记录" }
        val target = File(directory, "${pending.expectedVisualVersion}_$identitySha.json")
        if (target.isFile || File(target.path + ".bak").isFile) {
            val saved = JSONObject(readAtomic(target))
            require(saved.getString("state") == "aborted" && saved.getString("chat") == chatId &&
                saved.getString("identity_sha") == identitySha && saved.getString("png_sha") == pending.pngSha &&
                saved.getInt("expected_version") == pending.expectedVisualVersion) { "角色恢复记录校验失败" }
        } else {
            val audit = JSONObject().put("schema", 1).put("state", "aborted").put("reason", "superseded_by_profile")
                .put("chat", chatId).put("oc_id", pending.identity.ocId).put("base_revision", pending.expectedRevision)
                .put("pending_revision", pending.identity.revision).put("identity_sha", identitySha).put("png_sha", pending.pngSha)
                .put("expected_version", pending.expectedVisualVersion).put("superseded_version", version)
                .put("superseded_reference", currentReference?.let(::reference)).put("created_at", pending.createdAt)
                .put("aborted_at", System.currentTimeMillis())
            writeAtomic(target, audit.toString().toByteArray(Charsets.UTF_8))
        }
        val intent = pendingPath(chatId)
        AtomicFile(intent).delete()
        check(listOf(intent, File(intent.path + ".bak"), File(intent.path + ".new")).none { it.exists() }) { "旧形象保存状态暂时无法清理，请重试" }
    }
    fun finishPublication(chatId: String, attempt: String, committed: Boolean) = synchronized(lock) {
        val key = path(chatId).absolutePath
        if (activePublications[key] != attempt) return@synchronized
        try { if (committed) AtomicFile(pendingPath(chatId)).delete() }
        finally { activePublications.remove(key) }
    }
    fun commit(chatId: String, expectedRevision: Int, identity: OcIdentity): OcIdentitySnapshot = synchronized(lock) {
        val before = readUnlocked(chatId)
        val checked = OcIdentityRules.check(identity)
        require((before?.identity?.revision ?: 0) == expectedRevision && checked.revision == expectedRevision + 1 &&
            (before == null || before.identity.ocId == checked.ocId) && checked.confirmed) { "角色已更新，请重新打开编辑" }
        require(checked.route != "unknown" && checked.reference != null &&
            (checked.route != "photo_subject" || checked.photoSource != null)) { "请先确认角色与图片" }
        val sha = OcIdentityRules.packageSha(checked)
        val snapshot = OcIdentitySnapshot(checked, sha, System.currentTimeMillis())
        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "角色资料过大" }
        writeAtomic(path(chatId), bytes)
        snapshot
    }
    private fun writeAtomic(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "角色资料过大" }
        check(root.isDirectory || root.mkdirs()) { "角色保存目录不可用" }
        val file = AtomicFile(target); val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (failure: Throwable) { file.failWrite(out); throw failure }
    }
    private fun path(chatId: String): File {
        MediaRules.path(chatId)
        return File(root, "$chatId.json")
    }
    private fun pendingPath(chatId: String): File { MediaRules.path(chatId); return File(root, "$chatId.pending.json") }
    private fun readPendingUnlocked(chatId: String): OcPendingPublication? {
        val file = pendingPath(chatId)
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        val raw = readAtomic(file)
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1)
        val identity = decodeIdentity(json.getJSONObject("identity"))
        val revision = json.getInt("base_revision"); val version = json.getInt("expected_version")
        val pngSha = json.getString("png_sha"); val created = json.getLong("created_at")
        require(revision >= 0 && identity.revision == revision + 1 && identity.confirmed && version >= 0 && created > 0 &&
            Regex("[a-f0-9]{64}").matches(pngSha) && json.getString("identity_sha") == OcIdentityRules.packageSha(identity)) { "待保存角色校验失败" }
        return OcPendingPublication(identity, revision, version, pngSha, created)
    }
    private fun readUnlocked(chatId: String): OcIdentitySnapshot? {
        val file = AtomicFile(path(chatId))
        val raw = try { readAtomic(file.baseFile) } catch (failure: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
            return null
        }
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1) { "角色档案版本不受支持" }
        val identity = decodeIdentity(json.getJSONObject("identity"))
        val savedAt = json.getLong("saved_at")
        require(savedAt > 0 && json.getString("package_sha") == OcIdentityRules.packageSha(identity)) { "角色档案校验失败" }
        return OcIdentitySnapshot(identity, json.getString("package_sha"), savedAt)
    }
    private fun readAtomic(path: File): String = AtomicFile(path).openRead().use { stream ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= MAX_MANIFEST_BYTES) { "角色资料过大" }
                out.write(buffer, 0, count)
            }
            out.toByteArray().toString(Charsets.UTF_8)
    }
    private fun encode(snapshot: OcIdentitySnapshot): JSONObject = JSONObject().put("schema", 1)
        .put("package_sha", snapshot.packageSha).put("saved_at", snapshot.savedAt).put("identity", encodeIdentity(snapshot.identity))
    private fun encodeIdentity(v: OcIdentity): JSONObject = JSONObject().put("oc_id", v.ocId).put("revision", v.revision)
        .put("route", v.route).put("confirmed", v.confirmed).put("body", body(v.body))
        .put("parts", JSONArray(v.parts.map(::part))).put("rig", JSONObject().put("version", v.rig.version)
            .put("regions", JSONArray(v.rig.regions.map(::region))))
        .put("reference", v.reference?.let(::reference)).put("photo_source", v.photoSource?.let(::reference))
    private fun body(v: OcBody): JSONObject = JSONObject().put("head_width", v.headWidth).put("head_height", v.headHeight)
        .put("eye_size", v.eyeSize).put("eye_spacing", v.eyeSpacing).put("nose_size", v.noseSize).put("mouth_width", v.mouthWidth)
        .put("body_width", v.bodyWidth).put("height", v.height).put("hair_length", v.hairLength).put("hair_style", v.hairStyle)
        .put("skin", v.skin).put("hair", v.hair).put("eyes", v.eyes).put("clothes", v.clothes)
    private fun part(v: OcPart): JSONObject = JSONObject().put("id", v.id).put("kind", v.kind).put("side", v.side)
        .put("x", v.x).put("y", v.y).put("z", v.z).put("width", v.width).put("height", v.height)
        .put("angle", v.angle).put("color", v.color).put("layer", v.layer).put("label", v.label)
    private fun region(v: OcRigRegion): JSONObject = JSONObject().put("id", v.id).put("kind", v.kind)
        .put("x", v.x).put("y", v.y).put("width", v.width).put("height", v.height).put("pivot_x", v.pivotX)
        .put("pivot_y", v.pivotY).put("strength", v.strength).put("label", v.label)
    private fun reference(v: OcAssetRef): JSONObject = JSONObject().put("id", v.id).put("sha", v.sha256).put("visual_version", v.visualVersion)
    private fun decodeIdentity(v: JSONObject): OcIdentity {
        val parts = v.getJSONArray("parts"); val rig = v.getJSONObject("rig"); val regions = rig.getJSONArray("regions")
        require(parts.length() <= OcIdentityRules.MAX_PARTS && regions.length() <= 128) { "角色部件过多" }
        return OcIdentityRules.check(OcIdentity(v.getString("oc_id"), v.getInt("revision"), v.getString("route"), decodeBody(v.getJSONObject("body")),
            (0 until parts.length()).map { decodePart(parts.getJSONObject(it)) },
            OcRig((0 until regions.length()).map { decodeRegion(regions.getJSONObject(it)) }, rig.getInt("version")),
            v.optJSONObject("reference")?.let(::decodeReference), v.getBoolean("confirmed"), 1,
            v.optJSONObject("photo_source")?.let(::decodeReference)))
    }
    private fun decodeBody(v: JSONObject) = OcBody(v.f("head_width"), v.f("head_height"), v.f("eye_size"), v.f("eye_spacing"),
        v.f("nose_size"), v.f("mouth_width"), v.f("body_width"), v.f("height"), v.f("hair_length"),
        v.getInt("skin"), v.getInt("hair"), v.getInt("eyes"), v.getInt("clothes"), v.getString("hair_style"))
    private fun decodePart(v: JSONObject) = OcPart(v.getString("id"), v.getString("kind"), v.getString("side"),
        v.f("x"), v.f("y"), v.f("z"), v.f("width"), v.f("height"), v.f("angle"), v.getInt("color"), v.getInt("layer"), v.getString("label"))
    private fun decodeRegion(v: JSONObject) = OcRigRegion(v.getString("id"), v.getString("kind"), v.f("x"), v.f("y"),
        v.f("width"), v.f("height"), v.f("pivot_x"), v.f("pivot_y"), v.f("strength"), v.getString("label"))
    private fun decodeReference(v: JSONObject) = OcAssetRef(v.getString("id"), v.getString("sha"), v.getInt("visual_version"))
    private fun JSONObject.f(key: String) = getDouble(key).toFloat()
    companion object {
        private val lock = Any()
        private val activePublications = mutableMapOf<String, String>()
        const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}
