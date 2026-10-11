package com.zhiyu.app.domain

/** OK means the requested bounded paths finished, never that a missing fact never existed. */
object LocalRetrievalRules {
    fun fallback(reason: String, unresolvedCorrection: Boolean = false): LocalRetrievalStatus =
        if (reason in setOf("score_below_threshold", "no_text_sources") && !unresolvedCorrection) LocalRetrievalStatus.OK
        else LocalRetrievalStatus.PARTIAL

    fun factualStatus(status: LocalRetrievalStatus, historyMessages: Int, earlierSources: Int): String {
        require(historyMessages >= 0 && earlierSources >= 0)
        return "<retrieval_status>\n{\"state\":\"${status.wireValue}\",\"history_messages\":$historyMessages,\"earlier_source_count\":$earlierSources}\n</retrieval_status>"
    }
}

/** IDs can be switched independently in an offline evaluation, never through user prompt data. */
object LocalCompanionRules {
    fun render(scopes: Set<String>, disabledIds: Set<String> = emptySet()): String {
        val rules = CompanionContractGenerated.rules
        require(disabledIds.all { id -> rules.any { it.id == id } }) { "Unknown companion rule" }
        require(scopes.all { scope -> rules.any { scope in it.appliesTo } }) { "Unknown companion rule scope" }
        val chosen = rules.filter { it.id !in disabledIds && it.appliesTo.any(scopes::contains) }
        return "<behavior_rules>\n" + chosen.joinToString("\n") { "[${it.id}] ${it.text}" } + "\n</behavior_rules>"
    }
}
