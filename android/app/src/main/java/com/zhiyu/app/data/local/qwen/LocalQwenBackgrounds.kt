package com.zhiyu.app.data.local.qwen

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import com.zhiyu.app.data.*
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Installed-pack route owns durable asynchronous tasks; a null result is pending, never approval. */
class LocalQwenBackgrounds(private val context: Context) {
    private val jobs = QwenImageJobStore(context)
    private val media = LocalMediaStore(context)
    private val prompts = QwenScenePromptClient(appCredentialStore(context))
    fun hasInstalledPack(): Boolean = QwenPackStore(context).installed() != null
    suspend fun get(chat: String, day: String, version: Int, slot: LocalDailySlot): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!hasInstalledPack()) return@withContext null
            val candidate = snapshot(chat, day, version, slot)
            var job = jobs.registeredOrUnavailable(candidate.id)
            if (job == null) {
                jobs.write(candidate) // Reserve before the paid prompt call. An uncertain response is not retried.
                try {
                    val prompt = prompts.createPrompt(request(candidate))
                    job = candidate.copy(prompt = prompt, state = "prepared")
                    jobs.write(job!!)
                } catch (failure: Exception) {
                    withContext(NonCancellable) { jobs.write(candidate.copy(state = "prompt_unknown")) }
                    throw failure
                }
            }
            val stored = requireNotNull(job)
            if (stored.state == "approved") return@withContext decoded(stored)
            if (stored.state in setOf("prepared", "encoded", "sampled", "decoded")) QwenImageScheduler.enqueue(context)
            else throw LocalSceneBackgroundUnavailable("terminal")
            null
        }
    }
    suspend fun reviewComposite(chat: String, day: String, version: Int, slot: LocalDailySlot,
        png: ByteArray, referenceOriginal: ByteArray?): Boolean = lock.withLock { withContext(Dispatchers.IO) {
        val candidate = snapshot(chat, day, version, slot)
        val job = try { jobs.registeredOrUnavailable(candidate.id)?.takeIf { it.state == "approved" } }
            catch (_: LocalSceneBackgroundUnavailable) { null } ?: return@withContext false
        val sha = ChatPhotoRules.sha(png)
        val plan = LocalDailyRepository(context).use { it.planned(chat,version,day) }
        val index = plan.slots.indexOf(slot)
        require(index >= 0) { "生活取景已变化" }
        val view = LocalDayStockRules.view(index)
        val purpose = when {
            referenceOriginal == null -> QwenSceneReviewPurpose.BACKGROUND
            view == "portrait" -> QwenSceneReviewPurpose.COMPOSITE
            view == "pov" -> QwenSceneReviewPurpose.POV
            else -> return@withContext false
        }
        val referenceSha = referenceOriginal?.let(ChatPhotoRules::sha)
        if (purpose != QwenSceneReviewPurpose.BACKGROUND && referenceSha != job.referenceSha) return@withContext false
        val archive = jobs.file(job, "composite-$sha.png")
        if (!archive.exists()) FileOutputStream(archive).use { it.write(png); it.fd.sync() }
        require(ChatPhotoRules.sha(archive.readBytes()) == sha)
        val receipt = File(jobs.directory(job.id), "review-composite-$sha.json")
        if (receipt.exists()) return@withContext JSONObject(receipt.readText()).let {
            it.getString("image_sha") == sha && it.getString("status") == "APPROVED" &&
                it.optString("purpose") == purpose.name && it.optString("reference_sha") == referenceSha.orEmpty() &&
                it.optString("job") == job.id && isCurrent(job)
        }
        writeReview(receipt, job, sha, "UNKNOWN", purpose, referenceSha)
        val result = prompts.review(request(job), job.prompt, png, referenceOriginal,purpose)
        writeReview(receipt, job, sha, result.status.name,purpose,referenceSha)
        isCurrent(job) && result.status == QwenSceneReviewStatus.APPROVED && result.imageSha256 == sha
    } }

    internal fun request(job: QwenImageJob) = QwenScenePromptRequest(job.chat,job.day,job.scene,job.slot,
        job.activity,job.tags,job.referenceSha,job.version)
    internal suspend fun isCurrent(job: QwenImageJob): Boolean {
        if (job.day !in LocalScenePrefetchRules.days(Instant.now())) return false
        return snapshot(job.chat,job.day,job.version,LocalDailySlot(job.slot,job.activity,job.tags)).id == job.id
    }
    private suspend fun snapshot(chat: String, day: String, version: Int, slot: LocalDailySlot): QwenImageJob {
        require(day in LocalScenePrefetchRules.days(Instant.now())) { "生活图已超出预备日期" }
        val profile = LocalVisualRepository(context).profile(chat)
        require(profile.visualVersion == version) { "恋人外观已更新" }
        val character = OcCreatorStore(context).read(chat)?.takeIf {
            it.identity.reference?.id == profile.referenceId && it.identity.reference?.visualVersion == version
        }
        val original = character?.identity?.photoSource ?: profile.referenceId?.let { media.details(it).attachment.let { asset ->
            OcAssetRef(asset.id,asset.sha256,asset.visualVersion)
        } }
        val plan = LocalDailyRepository(context).use { it.planned(chat,version,day) }
        require(slot in plan.slots) { "生活轨迹已经更新" }
        val scene = plan.sceneId
        val request = QwenScenePromptRequest(chat,day,scene,slot.time,slot.activity,slot.tags,original?.sha256,version)
        val canonical = QwenScenePromptRules.metadata(request) + "|${original?.id}|${character?.packageSha}|${QwenRelease.MANIFEST_SHA}|prompt-v1"
        val id = LocalMemoryCodec.sha(canonical.toByteArray())
        val seed = id.take(8).toLong(16) and 0x7ffffffeL
        return QwenImageJob(id,chat,day,version,original?.id,original?.sha256,scene,slot.time,slot.activity,slot.tags,seed)
    }
    private fun decoded(job: QwenImageJob): Bitmap {
        val bytes = QwenDecodedPng.read(jobs.file(job, requireNotNull(job.decoded)))
        require(ChatPhotoRules.sha(bytes) == job.decodedSha) { "生活图片校验失败" }
        return QwenDecodedPng.decode(bytes)
    }
    private fun writeReview(file: File, job: QwenImageJob, sha: String, status: String,
        purpose: QwenSceneReviewPurpose, referenceSha: String?) {
        val atom = AtomicFile(file)
        val output = atom.startWrite()
        try { output.write(JSONObject().put("image_sha",sha).put("status",status).put("job",job.id)
            .put("purpose",purpose.name).put("reference_sha",referenceSha.orEmpty()).toString().toByteArray()); atom.finishWrite(output) }
        catch (failure: Throwable) { atom.failWrite(output); throw failure }
    }
    companion object { private val lock = Mutex() }
}
