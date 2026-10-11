package com.zhiyu.app.domain

import java.time.LocalDate

data class LocalGenerationJob(val requestId: String, val chatId: String, val kind: String,
    val expectedVersion: Int, val dayId: String, val promptHash: String, val state: String)

/** A lost response reserves its opportunity: retrying must never silently charge again. */
object LocalGenerationRules {
    val states = setOf("reserved", "running", "success", "failed", "unknown", "stale")
    private val unresolved = setOf("reserved", "running", "unknown")
    fun validate(job: LocalGenerationJob): LocalGenerationJob {
        MediaRules.path(job.requestId); MediaRules.path(job.chatId)
        require(job.kind in setOf("oc", "scene") && job.state in states && job.expectedVersion >= 0)
        require(Regex("[a-f0-9]{64}").matches(job.promptHash))
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(job.dayId))
        try { LocalDate.parse(job.dayId) } catch (error: Exception) {
            throw IllegalArgumentException("生成日期无效", error)
        }
        return job
    }
    fun quota(jobs: List<LocalGenerationJob>, configured: Boolean): GenerationQuota {
        jobs.forEach(::validate)
        val oc = jobs.filter { it.kind == "oc" }
        val used = oc.count { it.state in setOf("success", "stale") }
        val pending = oc.count { it.state in unresolved }
        return GenerationQuota(configured, used, pending, (2 - used - pending).coerceAtLeast(0))
    }
    fun canStart(jobs: List<LocalGenerationJob>, candidate: LocalGenerationJob): Boolean {
        validate(candidate); jobs.forEach(::validate)
        if (jobs.any { it.requestId == candidate.requestId }) return false
        return candidate.kind == "scene" || quota(jobs.filter { it.chatId == candidate.chatId }, true).remaining > 0
    }
}
