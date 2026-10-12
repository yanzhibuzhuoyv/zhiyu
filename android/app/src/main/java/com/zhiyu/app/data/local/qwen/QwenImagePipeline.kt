package com.zhiyu.app.data.local.qwen

import android.content.Context
import com.zhiyu.app.data.appCredentialStore
import com.zhiyu.app.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Each native stage releases its process before the next; only the decoded archive is permanent. */
class QwenImagePipeline(private val context: Context) {
    private val jobs = QwenImageJobStore(context)
    private val native = QwenNativeClient(context)
    private val backgrounds = LocalQwenBackgrounds(context)
    suspend fun run(initial: QwenImageJob) {
        val coroutine = currentCoroutineContext()
        if (!current(initial)) return
        QwenPackStore(context).verify(coroutine::ensureActive)
        var job = initial
        if (!current(job)) return
        if (job.state == "prepared") {
            val output = File(jobs.phaseDirectory(job), "conditions.f32")
            val result = native.run(job, "text", output)
            require(result.tokens in 1..498 && output.length() == result.tokens * 4096L * 4)
            job = job.copy(state = "encoded",tokens = result.tokens,condition = jobs.relative(job,output))
            jobs.write(job)
        }
        if (!current(job)) return
        if (job.state == "encoded") job = sample(job)
        if (!current(job)) return
        if (job.state == "sampled") job = decode(job)
        if (!current(job)) return
        if (job.state == "decoded") review(job)
    }
    private suspend fun current(job: QwenImageJob): Boolean {
        currentCoroutineContext().ensureActive()
        val current = try { backgrounds.isCurrent(job) } catch (_: IllegalArgumentException) { false }
        if (!current) jobs.write(job.copy(state = "stale"))
        return current
    }
    private suspend fun sample(initial: QwenImageJob): QwenImageJob {
        var job = initial
        val prior = job.checkpoint?.let { jobs.file(job,it) }
        if (prior != null && prior.isFile && validCheckpoint(job,prior)) {
            return job.copy(state = "sampled").also(jobs::write)
        }
        val output = File(jobs.phaseDirectory(job), "sample.qwenlatent")
        job = job.copy(checkpoint = jobs.relative(job,output))
        jobs.write(job) // A complete CRC-valid checkpoint may recover a process interruption after native write.
        native.run(job,"sample",output)
        require(validCheckpoint(job,output)) { "本机采样检查点与任务不一致" }
        return job.copy(state = "sampled").also(jobs::write)
    }
    private suspend fun validCheckpoint(job: QwenImageJob, path: File): Boolean = try {
        val metadata = JSONObject(requireNotNull(native.run(job,"inspect",path).metadata))
        metadata.getInt("version") == 1 && metadata.getInt("steps") == 8 && metadata.getLong("seed") == job.seed + job.attempt &&
            metadata.getLong("bytes") == 262288L && metadata.getBoolean("crc_verified") && metadata.getBoolean("finite") &&
            metadata.getString("stage") == "diffusion_normalized"
    } catch (failure: QwenProcessDeferred) { throw failure }
    catch (failure: QwenResourceDeferred) { throw failure }
    catch (_: IllegalArgumentException) { false }
    catch (_: IllegalStateException) { false }
    private suspend fun decode(job: QwenImageJob): QwenImageJob {
        val target = jobs.file(job,"decoded-${job.attempt}.png")
        if (!target.exists()) {
            val output = File(jobs.phaseDirectory(job),"decoded.png")
            native.run(job,"decode",output)
            val bytes = QwenDecodedPng.read(output)
            QwenDecodedPng.decode(bytes).recycle()
            val temporary = File(target.parentFile,"decoded-${job.attempt}.partial")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        }
        val bytes = QwenDecodedPng.read(target)
        QwenDecodedPng.decode(bytes).recycle()
        return job.copy(state = "decoded",decoded = jobs.relative(job,target),decodedSha = ChatPhotoRules.sha(bytes)).also(jobs::write)
    }
    private suspend fun review(job: QwenImageJob) {
        val bytes = QwenDecodedPng.read(jobs.file(job,requireNotNull(job.decoded)))
        require(ChatPhotoRules.sha(bytes) == job.decodedSha)
        jobs.write(job.copy(state = "review_unknown")) // Never repeat an uncertain paid review automatically.
        val result = QwenScenePromptClient(appCredentialStore(context)).review(backgrounds.request(job),job.prompt,bytes)
        require(result.imageSha256 == job.decodedSha)
        if (!current(job)) return
        when (result.status) {
            QwenSceneReviewStatus.APPROVED -> jobs.write(job.copy(state = "approved"))
            QwenSceneReviewStatus.REJECTED -> {
                jobs.write(job.copy(state = "rejected"))
                if (job.attempt == 0 && result.correction != null) jobs.write(job.copy(state = "prepared",attempt = 1,
                    prompt = result.correction,tokens = 0,condition = null,checkpoint = null,decoded = null,decodedSha = null))
            }
            else -> jobs.write(job.copy(state = "review_unknown"))
        }
    }
}
