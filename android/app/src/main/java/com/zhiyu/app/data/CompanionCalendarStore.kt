package com.zhiyu.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.zhiyu.app.domain.CalendarDraft
import java.time.LocalDate

class CompanionCalendarStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val preferences = appContext.getSharedPreferences("companion_calendar", Context.MODE_PRIVATE)
    private val sink = AndroidCompanionCalendarSink(appContext)
    private val writer = CompanionCalendarWriter(sink)

    fun allowed(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }
    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled", enabled && allowed()).commit()) { "日历权限设置没有保存成功" }
    }
    fun promptPlan(latestUserText: String): CompanionCalendarPlan {
        val enabled = allowed() && preferences.getBoolean("enabled", false)
        val today = LocalDate.now()
        // Failed reads never produce mutation targets, and do not block the user's ordinary chat.
        val candidates = if (!enabled) emptyList() else try { sink.candidates(today, latestUserText) }
            catch (_: Exception) { emptyList() }
        return CompanionCalendarProtocol.prepare(enabled, today, latestUserText, candidates)
    }

    /** Call after the assistant original is committed, on Dispatchers.IO, never inside its SQLite transaction. */
    fun commit(messageId: String, reply: CompanionCalendarReply): CalendarWriteOutcome = commitAllowed {
        when {
            reply.mutation != null -> writer.mutate(messageId, reply.mutation)
            reply.draft != null && reply.kind != null -> writer.write(messageId, reply.draft, reply.kind)
            else -> CalendarWriteOutcome(false)
        }
    }
    fun commit(messageId: String, draft: CalendarDraft?, kind: String?): CalendarWriteOutcome =
        commit(messageId, CompanionCalendarReply("", draft, kind))

    private fun commitAllowed(action: () -> CalendarWriteOutcome): CalendarWriteOutcome = try {
        if (!allowed() || !preferences.getBoolean("enabled", false)) CalendarWriteOutcome(false) else action()
    } catch (_: Exception) {
        CalendarWriteOutcome(false, notice = "聊天已保存，这次日历没能写入。你仍可以手动记下这一刻。")
    }
}
