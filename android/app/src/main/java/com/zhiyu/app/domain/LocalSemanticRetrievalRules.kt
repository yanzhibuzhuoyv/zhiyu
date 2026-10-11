package com.zhiyu.app.domain

enum class LocalSemanticRoute { SEMANTIC, SUPPLEMENT, LEXICAL }

/** Thresholds came from a separately frozen calibration corpus, never the held-out cases. */
object LocalSemanticRetrievalRules {
    const val SEMANTIC_ONLY_THRESHOLD = .56
    const val CANDIDATE_THRESHOLD = .42
    const val CALIBRATION_ID = "calibration-v1"
    const val CALIBRATION_SHA256 = "7b9261f4a086463a95926917748c9fe0e838106c6d404afe8978f405396e2cc8"
    fun route(maximumCosine:Double?,complete:Boolean,
              semanticOnlyThreshold:Double=SEMANTIC_ONLY_THRESHOLD,candidateThreshold:Double=CANDIDATE_THRESHOLD):LocalSemanticRoute {
        require(candidateThreshold in 0.0..1.0 && semanticOnlyThreshold in candidateThreshold..1.0)
        require(maximumCosine == null || maximumCosine.isFinite() && maximumCosine in -1.0..1.0)
        if (maximumCosine == null || maximumCosine < candidateThreshold) return LocalSemanticRoute.LEXICAL
        return if (complete && maximumCosine >= semanticOnlyThreshold) LocalSemanticRoute.SEMANTIC else LocalSemanticRoute.SUPPLEMENT
    }
}
