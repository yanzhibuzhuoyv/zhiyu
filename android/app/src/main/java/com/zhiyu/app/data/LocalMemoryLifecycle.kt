package com.zhiyu.app.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

data class LocalMemoryLifecycleDecision(
    val id: String, val tier: String, val pinned: Boolean, val independentEvidence: Int,
    val reason: String, val evidenceIds: List<String>,
)

/** Counts source observations, never fuses or summarizes their original texts. */
object LocalMemoryLifecycle {
    const val VERSION = "native-lifecycle-v1"
    fun evaluate(observations: List<LocalMemoryAnnotation>, now: Instant): Map<String, LocalMemoryLifecycleDecision> {
        require(observations.size <= 1000)
        val unique = observations.groupBy { it.id }.map { (_, duplicates) ->
            require(duplicates.distinct().size == 1) { "原文标注ID存在冲突" }
            duplicates.first()
        }.sortedBy { Instant.parse(it.createdAt) }
        val groups = unique.filter { eligible(it, now) }.groupBy { listOf(it.owner, it.conversationId, it.role, it.subject, it.kind, it.topicKey) }
        return unique.associate { item ->
            val key = listOf(item.owner, item.conversationId, item.role, item.subject, item.kind, item.topicKey)
            item.id to decide(item, independent(groups[key].orEmpty()), now)
        }
    }

    private fun eligible(item: LocalMemoryAnnotation, now: Instant) =
        item.validity == "current" && item.role == "user" && item.subject == "user" &&
            item.polarity == "affirm" && !Instant.parse(item.createdAt).isAfter(now)

    private fun independent(group: List<LocalMemoryAnnotation>): List<LocalMemoryAnnotation> {
        val days = mutableSetOf<java.time.LocalDate>()
        val messages = mutableSetOf<String>()
        return group.filter { item ->
            val day = Instant.parse(item.createdAt).atOffset(ZoneOffset.UTC).toLocalDate()
            if (day in days || item.messageId in messages) false else { days.add(day); messages.add(item.messageId); true }
        }
    }

    private fun decide(item: LocalMemoryAnnotation, evidence: List<LocalMemoryAnnotation>, now: Instant): LocalMemoryLifecycleDecision {
        val current = item.validity == "current" && !Instant.parse(item.createdAt).isAfter(now)
        val pinned = current && (item.correction && item.role == "user" || item.kind == "promise" &&
            item.polarity == "affirm" && item.subject == item.role && item.promiseState in setOf("pending", "overdue"))
        val permanent = current && item.role == "user" && item.subject == "user" &&
            (item.kind == "profile" && (item.correction || item.polarity == "affirm") || item.explicitKeep)
        val elapsed = if (evidence.size < 2) 0 else Duration.between(Instant.parse(evidence.first().createdAt), Instant.parse(evidence.last().createdAt)).toDays()
        val long = eligible(item, now) && item.kind in setOf("wish", "preference", "habit") && evidence.size >= 3 && elapsed >= 6
        val tier = if (permanent) "permanent" else if (long) "long" else "mid"
        val reason = when { permanent -> if (item.correction) "explicit_correction" else "verified_user_profile_or_keep"
            long -> if (item.kind == "wish") "repeated_wish_attention" else "repeated_user_evidence"
            pinned -> if (item.correction) "current_correction" else "unresolved_promise"
            else -> "insufficient_or_uncertain_evidence" }
        return LocalMemoryLifecycleDecision(item.id, tier, pinned, evidence.size, reason, evidence.take(3).map { it.id })
    }

    fun factor(decision: LocalMemoryLifecycleDecision, relevance: Double): Double {
        require(relevance.isFinite() && relevance >= 0)
        require(decision.tier in setOf("mid", "long", "permanent"))
        if (relevance == 0.0) return 1.0
        val tierBonus = when (decision.tier) { "long" -> .05; "permanent" -> .10; else -> 0.0 }
        return 1.0 + tierBonus + if (decision.pinned) .05 else 0.0
    }

    fun canCool(decisions: List<LocalMemoryLifecycleDecision>, recognitionPending: Boolean, manualTier: String?, automaticEnabled: Boolean = false): Boolean =
        automaticEnabled && !recognitionPending && manualTier != "permanent" && decisions.none { it.pinned || it.tier == "permanent" }
}
