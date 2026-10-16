package com.zhiyu.app.data

enum class VoiceGender(val label: String) { FEMALE("女声"), MALE("男声"), UNKNOWN("未分类") }

/** Gender is joined from the unchanged model speaker order and the corpus provider's metadata. */
object LocalVoiceCatalog {
    const val MODEL_SPEAKERS_SHA256 = "50a825fc9a89172eee04a4d68869d933d0eca0d847f3b6fcffff9514cc72442d"
    const val METADATA_SHA256 = "86148f95beab318ad9d08bf945b1d56ffba8c97b09137ae784cc62b0cd6539ef"
    private val male = setOf(10,15,21,23,25,32,40,46,48,52,58,60,65,69,72,74,75,76,83,95,98,100,
        102,107,110,119,124,136,140,143,151,153,156,163,167,171)
    private val female = setOf(0,1,2,3,4,5,6,7,8,9,11,12,13,14,16,17,18,19,20,22,24,26,27,28,29,
        30,31,33,34,35,36,37,38,39,41,42,43,44,45,47,49,50,51,53,54,55,56,57,59,61,62,63,64,66,
        67,68,70,71,73,77,78,79,80,81,82,84,85,86,87,88,89,90,91,92,93,94,96,97,99,101,103,104,
        105,106,108,109,111,112,113,114,115,116,117,118,120,121,122,123,125,126,127,128,129,130,131,
        132,133,134,135,137,138,139,141,142,144,145,146,147,148,149,150,152,154,155,157,158,159,160,
        161,162,164,165,166,168,169,170,172,173)

    fun gender(id: Int): VoiceGender {
        require(id in 0 until LocalVoiceRules.SPEAKER_COUNT) { "请选择有效的内置音色" }
        return when (id) { in female -> VoiceGender.FEMALE; in male -> VoiceGender.MALE; else -> VoiceGender.UNKNOWN }
    }
    fun ids(group: VoiceGender): List<Int> = (0 until LocalVoiceRules.SPEAKER_COUNT).filter { gender(it) == group }
}
