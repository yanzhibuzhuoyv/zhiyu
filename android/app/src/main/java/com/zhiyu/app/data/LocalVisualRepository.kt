package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ai.onnxruntime.OrtException
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** OC state and pending masks live only in this app's private phone storage. */
class LocalVisualRepository(context: Context,
    private val imageClient: AgnesImageClient = AgnesImageClient(),
    private val imageKey: () -> String? = { AgnesCredentialStore(context).read() },
    private val jobs: LocalImageJobStore = LocalImageJobStore(context),
    private val beforeProfileCommit: () -> Unit = {}) {
    private val prefs = context.applicationContext.getSharedPreferences("local_visual_profiles", Context.MODE_PRIVATE)
    private val assets = LocalMediaStore(context)
    private val processor = LocalPortraitProcessor(context)
    private fun chat(id: String): String { MediaRules.path(id); return "profile_$id" }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { synchronized(LocalMediaStore.lock) { block() } }
    suspend fun profile(id: String): VisualProfile = io { readProfile(id) }
    /** Short commit boundary shared with OC updates; callers perform no network operation here. */
    internal fun <T> withCurrentProfile(id: String, block: (VisualProfile) -> T): T =
        synchronized(LocalMediaStore.lock) { block(readProfile(id)) }
    suspend fun templates(): List<VisualTemplate> = emptyList()
    suspend fun generatedCandidates(id: String): List<MediaAttachment> = io {
        chat(id); jobs.candidates(id).map { assets.details(it).attachment }
    }
    suspend fun useGeneratedCandidate(id: String, assetId: String): VisualProfile = io {
        require(assetId in jobs.candidates(id)) { "这张图片不属于当前恋人" }
        val before=readProfile(id); val candidate=assets.details(assetId).attachment
        val asset=assets.ingest("image",assets.download(assetId,candidate.sha256),candidate.sourceLabel,
            before.visualVersion+1,purpose="portrait")
        writeProfile(id,before.copy(mode="generate",visualVersion=before.visualVersion+1,referenceId=asset.id,referenceUrl=asset.url))
    }
    suspend fun publishCharacter(id: String, identity: OcIdentity, png: ByteArray, expectedVersion: Int): OcAssetRef = io {
        OcIdentityRules.check(identity); require(identity.confirmed && identity.route == "photo_subject") {
            "请通过导入或生成图片设置恋人外观"
        }
        val before = readProfile(id)
        require(before.visualVersion == expectedVersion) { "外观已经更新，请重新打开角色编辑器" }
        val asset = assets.ingest("image", png, "你制作的 OC", before.visualVersion + 1, purpose = "portrait")
        writeProfile(id, before.copy(mode = "import", visualVersion = before.visualVersion + 1,
            referenceId = asset.id, referenceUrl = asset.url))
        OcAssetRef(asset.id, asset.sha256, asset.visualVersion)
    }
    suspend fun useTemplate(id: String, templateId: String): VisualProfile {
        chat(id); MediaRules.templatePath(templateId)
        throw IllegalArgumentException("本机暂未安装这套 OC 样例，请导入自己的图片")
    }
    suspend fun update(id: String, mode: String, description: String, gender: String, hair: String): VisualProfile = io {
        require(mode in setOf("none", "import", "generate") && gender in setOf("unspecified", "female", "male", "other") &&
            hair in setOf("unspecified", "short", "long", "other")) { "外观选项无效" }
        validateDescription(description)
        val before = readProfile(id)
        val changed = before.mode != mode || before.gender != gender || before.hair != hair
        // Descriptive fields do not revoke a confirmed picture. Version still advances to
        // invalidate previews/jobs created before the change; only explicit removal clears it.
        val keep = mode != "none"
        writeProfile(id, before.copy(mode = mode, description = description.trim(), gender = gender, hair = hair,
            visualVersion = before.visualVersion + if (changed) 1 else 0,
            referenceId = if (keep) before.referenceId else null, referenceUrl = if (keep) before.referenceUrl else null))
    }
    suspend fun generate(id: String, requestId: String, description: String): VisualProfile {
        chat(id); validateDescription(description); require(description.isNotBlank()) { "请先描述恋人的外貌" }
        val normalizedId = requestId.replace("-", "").lowercase()
        MediaRules.path(normalizedId)
        val prompt = "生成一个成年原创角色的单人完整参考图。严格遵守用户设定的左右、数量和道具，不增加镜像或额外肢体。" +
            "简单纯色背景，人物与所有翅膀、尾巴、衣物和武器完整入镜。外貌设定：${description.trim()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray()).joinToString("") { "%02x".format(it) }
        val before = profile(id)
        val previous = withContext(Dispatchers.IO) { jobs.get(normalizedId) }
        if (previous != null) {
            require(previous.job.chatId == id && previous.job.promptHash == hash) { "生成请求与原描述不一致" }
            if (previous.job.state == "success" && previous.assetId == before.referenceId) return before
            if (previous.job.state == "success" && previous.assetId != null && previous.job.expectedVersion == before.visualVersion) {
                return io {
                    val current = readProfile(id)
                    require(current.visualVersion == previous.job.expectedVersion) { "当前外观已更新，候选图仍会保留" }
                    val asset = assets.details(previous.assetId).attachment
                    assets.download(asset.id, asset.sha256)
                    writeProfile(id, current.copy(mode = "generate", description = description.trim(), visualVersion = current.visualVersion + 1,
                        referenceId = asset.id, referenceUrl = asset.url))
                }
            }
            throw ApiFailure(409, "这个请求已有记录，请查看当前外观；未确认的请求不会再次扣费", "generation_reserved")
        }
        val key = withContext(Dispatchers.IO) { imageKey() }
            ?: throw ApiFailure(503, "请先设置 Agnes 图像服务 Key，生成机会已保留", "generation_unconfigured")
        val job = LocalGenerationJob(normalizedId, id, "oc", before.visualVersion,
            LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(), hash, "reserved")
        withContext(Dispatchers.IO) { jobs.reserve(job); jobs.finish(normalizedId, "running") }
        try {
            val image = imageClient.generate(key, prompt)
            return io {
                val now = readProfile(id)
                val asset = assets.ingest("image", image.bytes, "描述生成的 OC", before.visualVersion + 1, purpose = "portrait")
                if (now.visualVersion != before.visualVersion) {
                    jobs.finish(normalizedId, "stale", asset.id)
                    throw ApiFailure(409, "生成期间外观已经更新，已保存这张候选图并保留当前外观", "generation_stale")
                }
                jobs.finish(normalizedId, "success", asset.id)
                writeProfile(id, now.copy(mode = "generate", description = description.trim(), visualVersion = now.visualVersion + 1,
                    referenceId = asset.id, referenceUrl = asset.url))
            }
        } catch (error: Exception) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                if (jobs.get(normalizedId)?.job?.state in setOf("reserved", "running")) {
                    val rejected = (error as? ApiFailure)?.errorCode in setOf("image_auth_failed", "image_balance_insufficient",
                        "image_rate_limited", "image_request_invalid", "image_redirect_rejected", "image_service_busy")
                    jobs.finish(normalizedId, if (rejected) "failed" else "unknown")
                }
            }
            throw error
        }
    }
    suspend fun import(id: String, bytes: ByteArray): VisualProfile = io {
        val before = readProfile(id)
        val asset = assets.ingest("image", bytes, "OC 原图", before.visualVersion + 1, purpose = "portrait")
        attach(id, before, asset)
    }
    suspend fun previewSubject(id: String, bytes: ByteArray): PortraitPreview = io {
        val before = readProfile(id)
        val original = assets.ingest("image", bytes, "OC 原图", before.visualVersion + 1, purpose = "portrait")
        var automaticFailure: String? = null
        val result = try { processor.extract(bytes) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is IllegalArgumentException && error !is IllegalStateException && error !is IOException && error !is OrtException) throw error
                automaticFailure = (error.message ?: "人物自动分离失败").take(1000)
                processor.manualStart(bytes)
            }
        val subject = assets.ingest("image", result.subject, "OC 人物预览", before.visualVersion + 1, purpose = "portrait")
        val preview = PortraitRules.check(PortraitPreview(LocalMediaStore.identifier(), original.id, subject.id,
            original.url, subject.url, original.sha256, subject.sha256, result.width, result.height,
            result.bounds, before.visualVersion, result.method, true, "working_pixels", result.sourceWidth, result.sourceHeight))
        savePreview(id, preview, System.currentTimeMillis() + 24 * 60 * 60 * 1000L, automaticFailure)
        preview
    }
    suspend fun refineSubject(id: String, previewId: String, expectedSubjectSha: String,
        strokes: List<PortraitStroke>): PortraitPreview = io {
        val record = pending(id, previewId, expectedSubjectSha)
        val before = record.portraitPreview()
        val result = processor.refine(assets.download(before.originalId, before.originalSha256),
            assets.download(before.subjectId, before.subjectSha256), before.bounds, strokes)
        val subject = assets.ingest("image", result.subject, "OC 手动修整预览", before.expectedVisualVersion + 1, purpose = "portrait")
        val after = before.copy(subjectId = subject.id, subjectUrl = subject.url, subjectSha256 = subject.sha256,
            bounds = result.bounds, method = "manual_refinement")
        savePreview(id, after, record.getLong("expires"))
        after
    }
    suspend fun confirmSubject(id: String, previewId: String, choice: String, expectedSubjectSha: String): VisualProfile = io {
        require(choice in setOf("subject", "original")) { "请选择主体或原图" }
        val record = pending(id, previewId, expectedSubjectSha)
        val preview = record.portraitPreview()
        val before = readProfile(id)
        val chosen = if (choice == "original") preview.originalId else preview.subjectId
        if (choice == "subject") require(processor.hasVisiblePixels(assets.download(chosen, preview.subjectSha256))) { "请先用保留笔刷选中人物，再确认采用" }
        val result = attach(id, before, assets.details(chosen).attachment)
        // Profile version is already advanced; even an interrupted status write cannot allow reuse.
        record.put("confirmed", true)
        save("preview_$previewId", record)
        result
    }
    suspend fun download(id: String, expectedSha: String? = null, template: Boolean = false): ByteArray = io {
        require(!template) { "本机未安装该外观样例" }
        assets.download(id, expectedSha)
    }
    private fun pending(id: String, previewId: String, expectedSubjectSha: String): JSONObject {
        chat(id); MediaRules.path(previewId)
        val raw = prefs.getString("preview_$previewId", null) ?: throw ApiFailure(404, "人物预览不存在，请重新导入")
        val record = JSONObject(raw)
        if (record.getString("chat") != id) throw ApiFailure(404, "人物预览不存在")
        if (record.getBoolean("confirmed")) throw ApiFailure(409, "这张预览已经采用，请重新导入")
        if (record.getLong("expires") <= System.currentTimeMillis()) throw ApiFailure(409, "预览已过期，请重新导入")
        val preview = record.portraitPreview()
        if (preview.expectedVisualVersion != readProfile(id).visualVersion || preview.subjectSha256 != expectedSubjectSha)
            throw ApiFailure(409, "外观或人物预览已更新，请重新打开修整")
        return record
    }
    private fun attach(id: String, before: VisualProfile, asset: MediaAttachment): VisualProfile =
        writeProfile(id, before.copy(mode = "import", visualVersion = before.visualVersion + 1,
            referenceId = asset.id, referenceUrl = asset.url))
    private fun readProfile(id: String): VisualProfile {
        val stored = prefs.getString(chat(id), null)
        val result = if (stored == null) VisualProfile("none", "", "unspecified", "unspecified", 0, null, null,
            GenerationQuota(false, 0, 0, 2)) else JSONObject(stored).visualProfile()
        return result.copy(generation = LocalGenerationRules.quota(jobs.list(id), imageKey() != null))
    }
    private fun writeProfile(id: String, profile: VisualProfile): VisualProfile {
        val quota = LocalGenerationRules.quota(jobs.list(id), imageKey() != null)
        val value = JSONObject().put("mode", profile.mode).put("description", profile.description).put("gender", profile.gender)
            .put("hair", profile.hair).put("visual_version", profile.visualVersion).put("reference_id", profile.referenceId)
            .put("reference_url", profile.referenceUrl).put("generation", JSONObject().put("configured", quota.configured)
                .put("used", quota.used).put("pending", quota.pending).put("remaining", quota.remaining).put("limit", 2))
        save(chat(id), value)
        return profile.copy(generation = quota)
    }
    private fun savePreview(id: String, preview: PortraitPreview, expires: Long, automaticFailure: String? = null) {
        val value = JSONObject().put("chat", id).put("expires", expires).put("confirmed", false)
            .put("id", preview.id).put("original_id", preview.originalId).put("subject_id", preview.subjectId)
            .put("original_url", preview.originalUrl).put("subject_url", preview.subjectUrl)
            .put("original_sha256", preview.originalSha256).put("subject_sha256", preview.subjectSha256)
            .put("original_width", preview.originalWidth).put("original_height", preview.originalHeight)
            .put("bbox", JSONArray(preview.bounds)).put("expected_visual_version", preview.expectedVisualVersion)
            .put("method", preview.method).put("needs_confirmation", true).put("automatic_failure", automaticFailure)
            .put("coordinate_space", preview.coordinateSpace).put("source_width", preview.sourceWidth).put("source_height", preview.sourceHeight)
        save("preview_${preview.id}", value)
        check(prefs.edit().putString("active_preview_$id", preview.id).commit()) { "人物预览未保存" }
    }
    suspend fun activePreview(id: String): PortraitPreview? = io {
        chat(id)
        val previewId = prefs.getString("active_preview_$id", null) ?: return@io null
        MediaRules.path(previewId)
        val raw = prefs.getString("preview_$previewId", null) ?: throw IllegalStateException("本地人物预览状态缺失")
        val record = JSONObject(raw)
        require(record.getString("chat") == id) { "人物预览归属错误" }
        if (record.getBoolean("confirmed") || record.getLong("expires") <= System.currentTimeMillis()) return@io null
        val preview = record.portraitPreview()
        if (preview.expectedVisualVersion != readProfile(id).visualVersion) null else preview
    }
    suspend fun discardPreview(id: String, previewId: String) = io {
        chat(id); MediaRules.path(previewId)
        if (prefs.getString("active_preview_$id", null) == previewId)
            check(prefs.edit().remove("active_preview_$id").commit()) { "人物预览状态未保存" }
    }
    private fun save(key: String, value: JSONObject) {
        if(key.startsWith("profile_")) beforeProfileCommit()
        check(prefs.edit().putString(key, value.toString()).commit()) { "本地外观状态未保存" }
    }
    private fun validateDescription(value: String) {
        require(value.length <= 1000 && value.none { (it.code < 32 && it != '\n' && it != '\t') || Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.SURROGATE.toInt()) }) { "外观描述过长或包含无效字符" }
    }
}
