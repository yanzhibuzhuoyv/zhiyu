package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.LocalDailyShareEvent
import com.zhiyu.app.domain.LocalDailySharePlan

/** A separate derived schedule; no credentials, user messages or RawRAG are stored here. */
@Suppress("UNUSED_PARAMETER")
class LocalDailyShareStore(context: Context, name: String = "local_daily_share") {
    fun plan(id: String, day: String): LocalDailySharePlan? = error("daily share state not implemented")
    fun savePlan(id: String, plan: LocalDailySharePlan): LocalDailySharePlan = error("daily share state not implemented")
    fun finished(id: String, day: String): Set<String> = error("daily share state not implemented")
    fun usedSha(id: String): Set<String> = error("daily share state not implemented")
    fun event(id: String, requestId: String): LocalDailyShareEvent? = error("daily share state not implemented")
    fun refresh(id: String, event: LocalDailyShareEvent): LocalDailyShareEvent = error("daily share state not implemented")
    fun aliasEvent(id: String, requestId: String): LocalDailyShareEvent? = error("daily share state not implemented")
    fun rememberRequest(id: String, requestId: String, event: LocalDailyShareEvent): Unit = error("daily share state not implemented")
    fun finish(id: String, event: LocalDailyShareEvent, sha256: String? = null): Unit = error("daily share state not implemented")
    fun prune(id: String, day: String): Unit = error("daily share state not implemented")
}
