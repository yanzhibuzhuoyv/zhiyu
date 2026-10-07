package com.zhiyu.app.domain

/** The app supplies fixed presets; user training stays editable in the same relationship. */
object CompanionRules {
    fun normalize(value: CompanionProfile?): CompanionProfile? {
        if (value == null) return null
        require(value.mode in setOf("preset", "custom")) { "请选择有效的恋人设定方式" }
        require(value.style in setOf("gentle", "playful", "steady", "warm_v1", "warm_v2")) { "请选择有效的恋人性格" }
        require(value.role in setOf("girlfriend", "boyfriend", "partner")) { "请选择有效的恋人形象" }
        val cleaned = value.copy(name = value.name.trim(), userName = value.userName.trim(), instructions = value.instructions.trim())
        require(cleaned.name.codePointCount(0, cleaned.name.length) <= 24 && cleaned.userName.codePointCount(0, cleaned.userName.length) <= 24) { "名字与称呼最多24个字" }
        require(cleaned.instructions.codePointCount(0, cleaned.instructions.length) <= 2000) { "自行调教的设定最多2000个字" }
        require(listOf(cleaned.name, cleaned.userName).none { text -> text.any { it.code < 32 || it.code == 127 || it == '<' || it == '>' } }) { "名字与称呼含有不支持的字符" }
        require(cleaned.instructions.none { (it.code < 32 && it != '\n' && it != '\t') || it.code == 127 }) { "恋人设定含有不支持的控制字符" }
        require(cleaned.mode == "custom" || cleaned.instructions.isEmpty()) { "固定预设不接受自定义指令" }
        return cleaned
    }
    fun checkChange(binding: CompanionBinding?, profile: CompanionProfile?) {
        if (binding == null || !binding.profileConfigured) return
        if (binding.profile?.mode == "preset") require(profile == binding.profile) { "选定的恋人性格与设定不能更换" }
        else if (binding.profile?.mode == "custom") require(profile?.mode == "custom") { "自行调教需继续使用自己的恋人设定" }
        else require(profile?.mode != "preset") { "自行调教不能改选固定恋人预设" }
    }
}
