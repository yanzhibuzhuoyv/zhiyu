package com.zhiyu.app.data

import java.time.LocalDate

/** Conservative provenance check for a derived calendar action; original chat is never changed. */
internal object CompanionCalendarEvidence {
    private val uncertain = listOf("如果", "假如", "假设", "要是", "等我", "待我", "等确定", "等确认",
        "还没决定", "未决定", "不确定", "记不清", "考虑", "候选", "可能", "也许", "或许", "打算",
        "比如", "例如", "举例", "示例", "引用", "或者", "还是", "任选", "二选一")
    // A missed derived reminder is preferable to creating a negated/cancelled commitment.
    // This does not reject, delete or rewrite the source message in RAG.
    private val denied = listOf("不", "没", "未", "别", "并非", "取消", "作废", "撤销", "撤回", "改期", "作罢", "无效", "变卦", "推迟", "延期")
    private val questions = listOf("？", "?", "吗", "么", "能否", "是否", "可否", "可不可以", "能不能", "会不会", "行不行", "好不好", "想不想", "要不要", "愿不愿意")
    private val commands = listOf("写进日历", "写到日历", "记到日历", "记在日历", "日历里记", "放进日历")
    private val settled = listOf("约好", "说定", "定好了", "决定了", "确定", "就定", "已经订", "已经约",
        "我们一起", "咱们一起", "咱俩一起", "我们去", "咱们去", "陪我", "答应我")
    private val occasions = listOf("生日", "纪念日", "周年")
    private val datePattern = Regex("(?<![0-9])(?:([0-9]{4})年)?([0-9]{1,2}|[一二三四五六七八九十]{1,3})月([0-9]{1,2}|[一二三四五六七八九十]{1,3})(?:日|号)?(?![0-9一二三四五六七八九十])")
    private val isoPattern = Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])")

    fun allowed(user: String, date: String, evidence: String, kind: String, today: LocalDate): Boolean {
        if (evidence.length !in 6..16000 || !user.contains(evidence)) return false
        if ((uncertain + denied + questions).any(user::contains)) return false
        val expected = LocalDate.parse(date)
        return when (kind) {
            "plan" -> supportedYear(user, expected, today) && supportedYear(evidence, expected, today) &&
                (commands + settled).any(evidence::contains) && matchesDate(user, expected, today, false) &&
                matchesDate(evidence, expected, today, false)
            "occasion" -> !listOf("农历", "阴历", "闰月").any(user::contains) &&
                occasions.any(evidence::contains) && matchesDate(evidence, expected, today, true)
            else -> false
        }
    }

    fun unconfirmed(user: String): Boolean = (uncertain + questions + listOf("农历", "阴历", "闰月")).any(user::contains)

    fun supportedYear(evidence: String, target: LocalDate, today: LocalDate): Boolean {
        if (listOf("后年", "前年", "去年", "下年", "下一年").any(evidence::contains)) return false
        if (evidence.contains("明年") && target.year != today.year + 1) return false
        if (evidence.contains("今年") && target.year != today.year) return false
        return true
    }

    fun matchesDate(evidence: String, target: LocalDate, today: LocalDate, annual: Boolean): Boolean {
        val dates = isoPattern.findAll(evidence).mapNotNull {
            try { LocalDate.parse(it.value) } catch (_: java.time.DateTimeException) { null }
        }.toList()
        if (dates.any { if (annual) nextOccurrence(it.monthValue, it.dayOfMonth, today) == target else it == target }) return true
        if (datePattern.findAll(evidence).any { match ->
                val year = match.groupValues[1].toIntOrNull()
                val month = number(match.groupValues[2])
                val day = number(match.groupValues[3])
                if (annual) nextOccurrence(month, day, today) == target
                else (if (year != null) target.year == year else target.year in today.year..today.year + 1) &&
                    target.monthValue == month && target.dayOfMonth == day
            }) return true
        if (annual) return false
        val relative = when {
            evidence.contains("大后天") -> 3L
            evidence.contains("后天") -> 2L
            evidence.contains("明天") || evidence.contains("明晚") || evidence.contains("明早") -> 1L
            evidence.contains("今天") || evidence.contains("今晚") -> 0L
            else -> return false
        }
        return today.plusDays(relative) == target
    }

    private fun nextOccurrence(month: Int, day: Int, today: LocalDate): LocalDate? {
        for (year in today.year..today.year + 4) {
            val candidate = try { LocalDate.of(year, month, day) } catch (_: java.time.DateTimeException) { continue }
            if (!candidate.isBefore(today)) return candidate
        }
        return null
    }
    private fun number(value: String): Int {
        value.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        fun digit(text: String): Int = if (text.length == 1 && text.single() in digits) digits.indexOf(text.single()) + 1 else -1
        if (value == "十") return 10
        if (!value.contains('十')) return digit(value)
        val parts = value.split('十')
        if (parts.size != 2) return -1
        val tens = if (parts[0].isEmpty()) 1 else digit(parts[0])
        val ones = if (parts[1].isEmpty()) 0 else digit(parts[1])
        return if (tens < 1 || ones < 0) -1 else tens * 10 + ones
    }
}
