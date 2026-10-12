package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A separate derived schedule; no credentials, user messages or RawRAG are stored here. */
class LocalDailyShareStore(context: Context, name: String = "local_daily_share") {
    private val prefs = (context.applicationContext ?: context).getSharedPreferences(name, Context.MODE_PRIVATE)
    private data class Entry(val plan: LocalDailySharePlan, val finished: Set<String> = emptySet(),
        val sha: Map<String, String> = emptyMap(), val aliases: Map<String, String> = emptyMap())

    fun plan(id: String, day: String): LocalDailySharePlan? = synchronized(lock) {
        date(day)
        read(id).singleOrNull { it.plan.dayId == day }?.plan
    }
    fun savePlan(id: String, plan: LocalDailySharePlan): LocalDailySharePlan = synchronized(lock) {
        validate(plan)
        val rows = read(id)
        rows.singleOrNull { it.plan.dayId == plan.dayId }?.let { return@synchronized it.plan }
        write(id, retain(rows + Entry(plan), plan.dayId))
        plan
    }
    fun finished(id: String, day: String): Set<String> = synchronized(lock) {
        date(day)
        read(id).singleOrNull { it.plan.dayId == day }?.finished ?: emptySet()
    }
    fun usedSha(id: String): Set<String> = synchronized(lock) { read(id).flatMap { it.sha.values }.toSet() }
    fun event(id: String, requestId: String): LocalDailyShareEvent? = synchronized(lock) {
        uuid(requestId)
        read(id).flatMap { it.plan.events }.singleOrNull { it.requestId == requestId }
    }
    fun refresh(id: String, event: LocalDailyShareEvent): LocalDailyShareEvent = synchronized(lock) {
        val rows = read(id)
        val entry = registered(rows, event)
        val previous = entry.plan.events.single { it.requestId == event.requestId }
        require(event.copy(sceneId = previous.sceneId, revision = previous.revision, visualVersion = previous.visualVersion) == previous) {
            "日常分享的次数、活动、时间与事件编号不能重抽"
        }
        if (event.requestId in entry.finished) return@synchronized previous
        val replacement = entry.copy(plan = entry.plan.copy(events = entry.plan.events.map { if (it.requestId == event.requestId) event else it }))
        validate(replacement.plan)
        write(id, rows.map { if (it.plan.dayId == event.dayId) replacement else it })
        event
    }
    fun aliasEvent(id: String, requestId: String): LocalDailyShareEvent? = synchronized(lock) {
        uuid(requestId)
        read(id).firstNotNullOfOrNull { entry -> entry.aliases[requestId]?.let { eventId ->
            entry.plan.events.single { it.requestId == eventId }
        } }
    }
    fun rememberRequest(id: String, requestId: String, event: LocalDailyShareEvent): Unit = synchronized(lock) {
        uuid(requestId)
        val rows = read(id)
        val entry = registered(rows, event)
        val previous = rows.firstNotNullOfOrNull { it.aliases[requestId] }
        require(previous == null || previous == event.requestId) { "正在重试的日常分享不能切换事件" }
        if (previous != null) return@synchronized
        require(entry.aliases.size < 64) { "日常分享重试记录过多，原状态已保留" }
        val replacement = entry.copy(aliases = entry.aliases + (requestId to event.requestId))
        write(id, rows.map { if (it.plan.dayId == event.dayId) replacement else it })
    }
    fun finish(id: String, event: LocalDailyShareEvent, sha256: String? = null): Unit = synchronized(lock) {
        require(sha256 == null || SHA.matches(sha256)) { "日常图片摘要无效" }
        val rows = read(id)
        val entry = registered(rows, event)
        require(entry.plan.events.single { it.requestId == event.requestId } == event) { "日常分享状态已经更新" }
        val previous = entry.sha[event.requestId]
        require(previous == null || sha256 == null || previous == sha256) { "已发送图片不能替换" }
        val replacement = entry.copy(finished = entry.finished + event.requestId,
            sha = if (sha256 == null) entry.sha else entry.sha + (event.requestId to sha256))
        write(id, rows.map { if (it.plan.dayId == event.dayId) replacement else it })
    }
    fun prune(id: String, day: String): Unit = synchronized(lock) {
        val rows = read(id)
        val retained = retain(rows, day)
        if (retained != rows) write(id, retained)
    }

    private fun registered(rows: List<Entry>, event: LocalDailyShareEvent): Entry {
        uuid(event.requestId)
        return rows.singleOrNull { row -> row.plan.dayId == event.dayId && row.plan.events.any { it.requestId == event.requestId } }
            ?: throw IllegalArgumentException("日常分享事件尚未登记")
    }
    private fun retain(rows: List<Entry>, day: String): List<Entry> {
        val cutoff = date(day).minusDays(3)
        return rows.filter { date(it.plan.dayId) >= cutoff }.sortedBy { it.plan.dayId }.takeLast(4)
    }
    private fun read(id: String): List<Entry> {
        MediaRules.path(id)
        val raw = prefs.getString(id, null) ?: return emptyList()
        require(raw.length <= 65536) { "日常分享状态过大，原状态已保留" }
        val root = fields(JSONObject(raw), setOf("version", "plans"))
        require(number(root, "version") == 1) { "日常分享状态版本无效" }
        val array = root.getJSONArray("plans")
        require(array.length() <= 4) { "日常分享历史数量无效" }
        val rows = (0 until array.length()).map { parse(array.getJSONObject(it)) }
        require(rows.map { it.plan.dayId }.toSet().size == rows.size) { "日常分享日期重复" }
        require(rows.flatMap { it.aliases.keys }.toSet().size == rows.sumOf { it.aliases.size }) { "日常分享请求重复" }
        return rows
    }
    private fun write(id: String, rows: List<Entry>) {
        MediaRules.path(id)
        val raw = JSONObject().put("version", 1).put("plans", JSONArray(rows.map(::json))).toString()
        require(raw.length <= 65536) { "日常分享状态过大，原状态已保留" }
        check(prefs.edit().putString(id, raw).commit()) { "日常分享计划未保存，请重试" }
    }
    private fun json(entry: Entry): JSONObject = JSONObject().put("day", entry.plan.dayId).put("quota", entry.plan.quota)
        .put("events", JSONArray(entry.plan.events.map { event ->
            JSONObject().put("request", event.requestId).put("day", event.dayId).put("scene", event.sceneId)
                .put("revision", event.revision).put("visual", event.visualVersion).put("due", event.due.toString())
                .put("expires", event.expires.toString()).put("slot", JSONObject().put("time", event.slot.time)
                    .put("activity", event.slot.activity).put("tags", JSONArray(event.slot.tags)))
        })).put("finished", JSONArray(entry.finished.toList())).put("sha", JSONObject(entry.sha)).put("aliases", JSONObject(entry.aliases))

    private fun parse(row: JSONObject): Entry {
        fields(row, setOf("day", "quota", "events", "finished", "sha", "aliases"))
        val array = row.getJSONArray("events")
        require(array.length() <= 7)
        val events = (0 until array.length()).map { index ->
            val value = fields(array.getJSONObject(index), setOf("request", "day", "scene", "revision", "visual", "due", "expires", "slot"))
            val slot = fields(value.getJSONObject("slot"), setOf("time", "activity", "tags"))
            LocalDailyShareEvent(value.getString("request"), value.getString("day"), value.getString("scene"),
                value.getString("revision"), number(value, "visual"), LocalDailySlot(slot.getString("time"),
                    slot.getString("activity"), strings(slot.getJSONArray("tags"), 16)),
                Instant.parse(value.getString("due")), Instant.parse(value.getString("expires")))
        }
        val plan = LocalDailySharePlan(row.getString("day"), number(row, "quota"), events)
        validate(plan)
        val finished = strings(row.getJSONArray("finished"), 7)
        require(finished.toSet().size == finished.size && finished.all { key -> events.any { it.requestId == key } })
        val sha = mapping(row.getJSONObject("sha"), 7)
        require(sha.all { (key, digest) -> key in finished && SHA.matches(digest) })
        val aliases = mapping(row.getJSONObject("aliases"), 64)
        require(aliases.all { (key, target) -> uuid(key); events.any { it.requestId == target } })
        return Entry(plan, finished.toSet(), sha, aliases)
    }
    private fun validate(plan: LocalDailySharePlan) {
        val day = date(plan.dayId)
        require(plan.quota in 0..7 && plan.events.size <= plan.quota && plan.events.map { it.requestId }.toSet().size == plan.events.size)
        require(plan.events.map { it.slot.time }.toSet().size == plan.events.size)
        val zone = ZoneId.of(LocalDailyRules.TIMEZONE)
        plan.events.forEach { event ->
            uuid(event.requestId); MediaRules.path(event.sceneId)
            require(event.dayId == plan.dayId && SHA.matches(event.revision) && event.visualVersion >= 0)
            require(event.due < event.expires && event.expires <= event.due.plusSeconds(2700) &&
                event.due.atZone(zone).toLocalDate() == day && event.expires <= day.plusDays(1).atStartOfDay(zone).toInstant())
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(event.slot.time) &&
                event.slot.activity.length in 1..200 && event.slot.activity.none { it.isISOControl() } &&
                event.slot.tags.size in 1..16 && event.slot.tags.all { Regex("[a-z][a-z0-9_]{0,31}").matches(it) })
        }
    }
    private fun strings(array: JSONArray, limit: Int): List<String> {
        require(array.length() <= limit)
        return (0 until array.length()).map { index -> require(array.get(index) is String); array.getString(index) }
    }
    private fun mapping(value: JSONObject, limit: Int): Map<String, String> {
        require(value.length() <= limit)
        return value.keys().asSequence().associateWith { key -> require(value.get(key) is String); value.getString(key) }
    }
    private fun fields(value: JSONObject, expected: Set<String>): JSONObject {
        require(value.keys().asSequence().toSet() == expected) { "日常分享状态字段无效" }
        return value
    }
    private fun number(value: JSONObject, key: String): Int {
        val raw = value.get(key)
        require(raw is Int || raw is Long && raw in Int.MIN_VALUE..Int.MAX_VALUE) { "日常分享状态整数无效" }
        return (raw as Number).toInt()
    }
    private fun date(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        return LocalDate.parse(value).also { require(it.toString() == value) }
    }
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) { "日常分享请求标识无效" } }
    companion object { private val lock = Any(); private val SHA = Regex("[a-f0-9]{64}") }
}
