package com.zhiyu.app.domain

object BackgroundContentRules {
    const val SCHEMA = "background-content-v1"
    fun accepts(response: String): Boolean = try {
        require(response.length <= 8192)
        val value = AgnesImageJson.parse(response) as? Map<*, *> ?: throw IllegalArgumentException()
        require(value.keys == setOf("schema", "subject_free", "person", "body", "face", "character", "uncertain"))
        value["schema"] == SCHEMA && value["subject_free"] == true &&
            listOf("person", "body", "face", "character", "uncertain").all { value[it] == false }
    } catch (_: IllegalArgumentException) { false }
}
