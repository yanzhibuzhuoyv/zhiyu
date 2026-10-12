package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.LocalDailyScene
import com.zhiyu.app.domain.LocalDailyShareEvent
import com.zhiyu.app.domain.MediaAttachment
import java.time.Instant

/** Selects only already published, reviewed day-stock; never renders an on-demand substitute. */
class LocalDailyShareMedia(context: Context, private val now: () -> Instant = Instant::now) {
    fun scene(chat: String, at: Instant = now()): LocalDailyScene {
        throw UnsupportedOperationException("TDD pending: daily share current scene")
    }

    fun prepare(chat: String, event: LocalDailyShareEvent, usedSha: Set<String>): MediaAttachment? {
        throw UnsupportedOperationException("TDD pending: daily share media selection")
    }

    fun <T> validateAndCommit(chat: String, event: LocalDailyShareEvent, attachment: MediaAttachment,
        save: (MediaAttachment) -> T): T {
        throw UnsupportedOperationException("TDD pending: daily share final freshness check")
    }
}
