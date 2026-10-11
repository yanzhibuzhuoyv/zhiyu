package com.zhiyu.app.domain

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.round

data class ApproximateLocation(val latitude: Double, val longitude: Double) {
    override fun toString() = "ApproximateLocation(redacted)"
}

data class WeatherSnapshot(
    val observedAt: Long, val fetchedAt: Long, val temperatureC: Double,
    val feelsLikeC: Double, val weatherCode: Int, val precipitationMm: Double, val windSpeedKmh: Double,
)

data class CarePreferenceState(val enabled: Boolean = false, val weather: WeatherSnapshot? = null)
data class CalendarDraft(val title: String, val date: String, val description: String)

enum class CompanionPermission { CONTACT, WEATHER, CALENDAR }
object CompanionPermissionPolicy {
    fun next(requested: Set<CompanionPermission>, granted: Set<CompanionPermission>, blocked: Set<CompanionPermission> = emptySet()): CompanionPermission? =
        CompanionPermission.entries.firstOrNull { it !in requested && it !in granted && it !in blocked }
    fun afterAttempt(requested: Set<CompanionPermission>, permission: CompanionPermission, launched: Boolean): Set<CompanionPermission> =
        if (launched) requested + permission else requested - permission
}

object CareRules {
    const val MAX_AGE_SECONDS = 7200L
    const val REFRESH_INTERVAL_SECONDS = 900L
    const val MAX_RESPONSE_BYTES = 65_536
    private val conditions = mapOf(0 to "晴", 1 to "大致晴朗", 2 to "局部多云", 3 to "阴天", 45 to "雾", 48 to "冻雾",
        51 to "小毛毛雨", 53 to "毛毛雨", 55 to "强毛毛雨", 56 to "冻毛毛雨", 57 to "强冻毛毛雨",
        61 to "小雨", 63 to "中雨", 65 to "大雨", 66 to "冻雨", 67 to "强冻雨",
        71 to "小雪", 73 to "中雪", 75 to "大雪", 77 to "雪粒", 80 to "小阵雨", 81 to "阵雨", 82 to "强阵雨",
        85 to "小阵雪", 86 to "强阵雪", 95 to "雷暴", 96 to "雷暴伴冰雹", 97 to "强雷暴", 99 to "雷暴伴强冰雹")

    fun approximate(point: ApproximateLocation): ApproximateLocation {
        require(point.latitude.isFinite() && point.longitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) { "位置无效，请重试" }
        return ApproximateLocation(round(point.latitude * 10) / 10, round(point.longitude * 10) / 10)
    }

    fun weatherUrl(point: ApproximateLocation): String {
        val approximate = approximate(point)
        return "https://api.open-meteo.com/v1/forecast?latitude=${approximate.latitude}&longitude=${approximate.longitude}" +
            "&current=temperature_2m,apparent_temperature,weather_code,precipitation,wind_speed_10m" +
            "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm&timeformat=unixtime&timezone=GMT&forecast_days=1"
    }

    fun weather(snapshot: WeatherSnapshot): WeatherSnapshot {
        require(snapshot.observedAt > 0 && snapshot.fetchedAt > 0) { "天气更新时间无效" }
        require(snapshot.temperatureC.isFinite() && snapshot.temperatureC in -100.0..70.0) { "天气温度无效" }
        require(snapshot.feelsLikeC.isFinite() && snapshot.feelsLikeC in -120.0..100.0) { "天气体感温度无效" }
        require(snapshot.weatherCode in conditions) { "天气类型暂不支持" }
        require(snapshot.precipitationMm.isFinite() && snapshot.precipitationMm in 0.0..1000.0) { "天气降水数据无效" }
        require(snapshot.windSpeedKmh.isFinite() && snapshot.windSpeedKmh in 0.0..500.0) { "天气风速数据无效" }
        return snapshot
    }

    fun fresh(snapshot: WeatherSnapshot, now: Long): Boolean =
        now - snapshot.fetchedAt in -300L..MAX_AGE_SECONDS && now - snapshot.observedAt in -900L..MAX_AGE_SECONDS

    fun condition(snapshot: WeatherSnapshot): String = conditions[weather(snapshot).weatherCode] ?: error("未知天气")

    fun summary(snapshot: WeatherSnapshot): String = "${condition(snapshot)} · ${number(snapshot.temperatureC)}℃ · 体感 ${number(snapshot.feelsLikeC)}℃"

    fun prompt(state: CarePreferenceState, now: Long, permissionGranted: Boolean): String {
        val snapshot = state.weather ?: return ""
        if (!state.enabled || !permissionGranted || !fresh(snapshot, now)) return ""
        weather(snapshot)
        return "\n用户已开启天气关心。其粗略所在地最近的天气估计：${summary(snapshot)}，降水 ${number(snapshot.precipitationMm)} mm，风速 ${number(snapshot.windSpeedKmh)} km/h。" +
            "这些是天气服务的估计，不能当作用户亲口分享的日常，也不能声称看到了用户正在做什么。" +
            "依据已经设定的性格和当前话题适度关心，不要每条回复都谈天气，不强行追问位置，不改变人格。" +
            "你不拥有持续定位、跟踪或控制用户设备的能力，也不能因为角色控制欲较强就声称监视用户。"
    }

    fun calendar(draft: CalendarDraft): CalendarDraft {
        val title = draft.title.trim()
        val description = draft.description.trim()
        require(title.length in 1..100 && description.length <= 2000) { "标题需 1–100 字，日志内容最多 2000 字" }
        require(title.none { it.isISOControl() } && description.none { it.isISOControl() && it !in "\n\r\t" }) { "日历内容包含无效字符" }
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(draft.date)) { "日期格式请填写 YYYY-MM-DD" }
        val date = try { LocalDate.parse(draft.date) } catch (_: DateTimeParseException) { throw IllegalArgumentException("日期不存在，请检查年月日") }
        require(date.year in 1900..2200) { "年份请填写 1900–2200" }
        return draft.copy(title = title, description = description)
    }

    fun calendarTimes(draft: CalendarDraft): Pair<Long, Long> {
        val date = LocalDate.parse(calendar(draft).date)
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun number(value: Double) = String.format(Locale.ROOT, "%.1f", value)
}
