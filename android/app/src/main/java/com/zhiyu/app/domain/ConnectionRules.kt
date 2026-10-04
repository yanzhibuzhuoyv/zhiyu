package com.zhiyu.app.domain

import java.net.URI

object ConnectionRules {
    const val MAX_FILE_BYTES = 5 * 1024 * 1024
    fun normalizeUrl(value: String, allowHttp: Boolean): String {
        val text = value.trim().trimEnd('/')
        val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("后端地址格式不正确") }
        require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) {
            if (allowHttp) "请输入 http:// 或 https:// 后端地址" else "正式版仅允许 HTTPS 后端地址"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "后端地址不能包含账号、密码、查询参数或片段"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "后端端口不正确" }
        return text
    }
    fun message(value: String): String = value.trim().also {
        require(it.isNotBlank()) { "请输入消息" }
        require(it.length <= 16000) { "单条消息不能超过 16000 个字符" }
    }
    fun documentName(name: String, size: Int): String {
        require(size > 0) { "文件内容为空" }
        require(size <= MAX_FILE_BYTES) { "单个文件不能超过 5 MB" }
        require(name.substringAfterLast('.', "").lowercase() in setOf("txt", "md", "pdf", "docx")) {
            "请选择 TXT、Markdown、PDF 或 DOCX 文件"
        }
        return name.substringAfterLast('/').substringAfterLast('\\').take(200)
    }
}
