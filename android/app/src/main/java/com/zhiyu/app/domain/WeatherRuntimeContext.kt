package com.zhiyu.app.domain

object WeatherRuntimeContext {
    fun render(state: CarePreferenceState, now: Long, permissionGranted: Boolean): String {
        val status = when {
            !permissionGranted -> "permission_missing"
            !state.enabled -> "disabled"
            state.weather == null -> "awaiting_fix"
            !CareRules.fresh(state.weather, now) -> "stale"
            else -> "ready"
        }
        return "weather_supported=true\nweather_state=$status\n" + if (status == "ready")
            CareRules.prompt(state, now, true) + "天气已可用，不能再说永远看不到天气或只能靠用户告知。" else
            "本轮没有可用的当前天气事实。不能捏造温度或下雨，也不把定位尚未就绪/缓存过期说成永久不能查询天气；需要时简短说明暂时还没取得。"
    }
    fun requested(text: String): Boolean = text.length <= 2000 &&
        !Regex("昨天|昨晚|故事|我叫").containsMatchIn(text) &&
        Regex("(?:天气|多少度|气温|温度|下雨|带伞|冷不冷|热不热).{0,12}(?:[吗么呢？?]|怎么样)|(?:天气怎么样|多少度|要带伞)").containsMatchIn(text)
}
