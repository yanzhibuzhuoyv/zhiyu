package com.zhiyu.app.data

import com.zhiyu.app.domain.CalendarDraft
import com.zhiyu.app.domain.CareRules
import java.time.LocalDate

/** A bounded, app-owned event snapshot. IDs never come from model output. */
data class CompanionCalendarCandidate(val sourceKey: String, val eventId: Long, val calendarId: Long,
    val draft: CalendarDraft, val kind: String, val revision: String) {
    init {
        require(Regex("[a-f0-9]{64}").matches(sourceKey) && Regex("[a-f0-9]{64}").matches(revision))
        require(eventId > 0 && calendarId > 0 && kind in setOf("diary", "plan", "occasion"))
        CareRules.calendar(draft)
    }
    fun occurrence(today: LocalDate): LocalDate {
        val original = LocalDate.parse(draft.date)
        if (kind != "occasion") return original
        for (year in maxOf(today.year, original.year)..maxOf(today.year, original.year) + 4) {
            val next = try { original.withYear(year) } catch (_: java.time.DateTimeException) { continue }
            // withYear clamps February 29; annual leap-day birthdays must not become February 28.
            if (next.monthValue == original.monthValue && next.dayOfMonth == original.dayOfMonth && !next.isBefore(today)) return next
        }
        throw IllegalArgumentException("纪念日期无效")
    }
}

data class CompanionCalendarMutation(val operation: String, val target: CompanionCalendarCandidate, val draft: CalendarDraft?) {
    init {
        require(target.kind != "diary" && operation in setOf("cancel", "reschedule"))
        require((operation == "cancel") == (draft == null))
        draft?.let { require(CareRules.calendar(it).title == target.draft.title) }
    }
}

internal data class CompanionCalendarSchedule(val recurrence: String?, val duration: String?, val reminders: List<Int>) {
    companion object {
        fun of(draft: CalendarDraft, kind: String): CompanionCalendarSchedule {
            val date = LocalDate.parse(CareRules.calendar(draft).date)
            return when (kind) {
                "diary" -> CompanionCalendarSchedule(null, null, emptyList())
                "plan" -> CompanionCalendarSchedule(null, null, listOf(60))
                "occasion" -> CompanionCalendarSchedule("FREQ=YEARLY;BYMONTH=${date.monthValue};BYMONTHDAY=${date.dayOfMonth}", "P1D", listOf(1440))
                else -> throw IllegalArgumentException("日历类型无效")
            }
        }
    }
}

/** Full-current-message checks are independent of model claims and preserve all original chat text. */
internal object CompanionCalendarMutationEvidence {
    private val cancelWords = listOf("取消", "撤销", "作废", "撤回", "作罢")
    private val changePattern = Regex("改到|改为|改成|更正为")
    private val negated = listOf("不要", "不用", "别", "没", "未", "不想", "不能", "不会", "并非", "不是", "不取消", "不撤销", "不作废", "不改")
    private val generic = setOf("一起", "我们", "约定", "准备", "安排", "日记", "计划", "陪我", "想你")
    private val clauseSeparator = Regex("[，,。；;！!？?\\r\\n]+")
    private val preserved = Regex("照常|保留|继续|照旧|维持|依旧|不变")
    private val cancelPattern = Regex(cancelWords.joinToString("|"))
    private val dateWrapper = "(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|(?:[0-9]{4}年)?[0-9一二三四五六七八九十]{1,3}月[0-9一二三四五六七八九十]{1,3}[日号]?)"
    private val wrappers = "(?:我们|咱们|咱俩|一起|陪我|陪你|约好|准备|安排|计划|我的|你的|我|你|的|把|去|观看|看|吃|但是|不过|但|而|仍然|依旧|继续|照常|保留|请|还|先|就|大后天|后天|明天|明晚|明早|今天|今晚|$dateWrapper)"
    private val eventNouns = "(?:的)?(?:约定|安排|计划|活动|准备)"
    private val bindingFillers = Regex("(?:\\s|的|约定|安排|计划|活动|准备|已经|刚刚|现在|就|先|直接|正式|决定|确定|全部|被|了|吧)*")

    fun allowed(user: String, evidence: String, operation: String, candidate: CompanionCalendarCandidate,
                date: String, today: LocalDate): Boolean {
        if (user.length !in 6..16000 || evidence != user || candidate.kind == "diary") return false
        if (CompanionCalendarEvidence.unconfirmed(user) || negated.any(user::contains)) return false
        val clauses = user.split(clauseSeparator).filter { it.isNotBlank() }
        if (clauses.any { preserved.containsMatchIn(it) && activityMatches(it, candidate) }) return false
        val oldDate = candidate.occurrence(today)
        if (operation == "cancel") return date == oldDate.toString() && clauses.count {
            cancellationBinds(it, candidate) && matches(it, oldDate, today, candidate.kind)
        } == 1
        if (operation != "reschedule") return false
        if (changePattern.findAll(user).count() != 1) return false
        val clause = clauses.singleOrNull { changePattern.containsMatchIn(it) } ?: return false
        val change = changePattern.find(clause) ?: return false
        val before = clause.substring(0, change.range.first)
        val after = clause.substring(change.range.last + 1)
        val destination = try { LocalDate.parse(date) } catch (_: java.time.DateTimeException) { return false }
        return destination != oldDate && !destination.isBefore(today) && activityMatches(before, candidate) &&
            changeBinds(before, candidate) && matches(before, oldDate, today, candidate.kind) && matches(after, destination, today, candidate.kind)
    }
    private fun matches(text: String, date: LocalDate, today: LocalDate, kind: String): Boolean =
        CompanionCalendarEvidence.supportedYear(text, date, today) &&
            CompanionCalendarEvidence.matchesDate(text, date, today, kind == "occasion")

    private fun anchors(candidate: CompanionCalendarCandidate): List<String> {
        val title = candidate.draft.title.lowercase().replace(
            Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"), " ")
        val words = Regex("[\\p{L}]{2,}").findAll(title).map { it.value }.toList()
        // Keep activity verbs: watching a movie and making a movie are different plans.
        val prefix = Regex("^(?:(?:我们|咱们|咱俩|一起|陪我|陪你|约定|约好|准备|安排|计划))+")
        val suffix = Regex("(?:(?:准备|安排|计划|约定))+$")
        return words.map { it.replace(prefix, "").replace(suffix, "") }
            .filter { it.length >= 2 && it !in generic }.distinct()
    }
    private fun activityPattern(anchor: String): Regex {
        val left = "(?:(?<![\\p{L}])|(?<=取消)|(?<=撤销)|(?<=作废)|(?<=撤回)|(?<=作罢))"
        val right = "(?=$|[^\\p{L}\\p{N}]|$eventNouns|取消|撤销|作废|撤回|作罢|照常|保留|继续|照旧|维持|依旧|不变|改到|改为|改成|更正为)"
        // Atomic alternatives and bounded wrappers prevent pathological prefix backtracking.
        return Regex("$left(?:(?>$wrappers)\\s*+){0,24}?${Regex.escape(anchor)}$right", RegexOption.IGNORE_CASE)
    }
    private fun activityMatches(text: String, candidate: CompanionCalendarCandidate): Boolean {
        val anchors = anchors(candidate)
        return anchors.isNotEmpty() && anchors.all { activityPattern(it).containsMatchIn(text) }
    }
    private fun activitySpan(text: String, candidate: CompanionCalendarCandidate): IntRange? {
        val anchors = anchors(candidate)
        if (anchors.isEmpty()) return null
        val spans = anchors.map { activityPattern(it).findAll(text).toList().singleOrNull()?.range ?: return null }
        return spans.minOf { it.first }..spans.maxOf { it.last }
    }
    private fun cancellationBinds(clause: String, candidate: CompanionCalendarCandidate): Boolean {
        val span = activitySpan(clause, candidate) ?: return false
        val action = cancelPattern.findAll(clause).toList().singleOrNull()?.range ?: return false
        val between = when {
            span.last < action.first -> clause.substring(span.last + 1, action.first)
            action.last < span.first -> clause.substring(action.last + 1, span.first)
            else -> return false
        }
        return between.length <= 128 && bindingFillers.matches(between)
    }
    private fun changeBinds(before: String, candidate: CompanionCalendarCandidate): Boolean {
        val span = activitySpan(before, candidate) ?: return false
        val between = before.substring(span.last + 1)
        return between.length <= 128 && bindingFillers.matches(between)
    }
}
