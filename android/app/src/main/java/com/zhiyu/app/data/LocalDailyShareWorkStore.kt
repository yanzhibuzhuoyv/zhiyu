package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*

/** Independent pending/run state, while opt-in and notification claims are shared. */
@Suppress("UNUSED_PARAMETER")
class LocalDailyShareWorkStore(context: Context, name: String = "daily_share_delivery",
    private val notificationState: ProactiveWorkStore = ProactiveWorkStore(context),
) : ProactiveWorkState {
    override fun enabled(conversationId: String): Boolean = error("daily share work state not implemented")
    override fun requestId(conversationId: String): String = error("daily share work state not implemented")
    override fun complete(conversationId: String, requestId: String): Unit = error("daily share work state not implemented")
    override fun claimMessage(messageId: String): Boolean = error("daily share work state not implemented")
    override fun recordRun(conversationId: String, record: ProactiveRunRecord): Unit = error("daily share work state not implemented")
    fun lastRun(conversationId: String): ProactiveRunRecord? = error("daily share work state not implemented")
}
