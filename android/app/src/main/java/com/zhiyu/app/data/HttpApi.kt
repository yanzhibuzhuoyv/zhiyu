package com.zhiyu.app.data

import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** No network logging, permissive TLS, redirects or third-party credential interceptors. */
class HttpApi {
    suspend fun request(url: String, path: String, method: String = "GET", token: String? = null, json: JSONObject? = null): String =
        call(url, path, method, token, "application/json", json?.toString()?.toByteArray(Charsets.UTF_8))

    suspend fun upload(url: String, path: String, token: String, name: String, bytes: ByteArray): String {
        val boundary = "ZhiYu-${UUID.randomUUID()}"
        val safeName = name.replace(Regex("[\\r\\n\"\\\\]"), "_")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray(Charsets.UTF_8) + bytes + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return call(url, path, "POST", token, "multipart/form-data; boundary=$boundary", body)
    }

    private suspend fun call(url: String, path: String, method: String, token: String?, contentType: String, body: ByteArray?): String =
        withContext(Dispatchers.IO) {
            val connection = URL(url + path).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 120000
                connection.requestMethod = method
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw ApiFailure(status, errorMessage(status, response))
                response
            } catch (error: SocketTimeoutException) {
                throw ApiFailure(0, "后端响应超时。消息可能已经保存，请刷新会话后再决定是否重发")
            } catch (error: java.io.IOException) {
                throw ApiFailure(0, "无法连接后端，请检查地址、网络和后端是否启动")
            } finally { connection.disconnect() }
        }

    private fun errorMessage(status: Int, response: String): String {
        val detail = runCatching { JSONObject(response).opt("detail") }.getOrNull()
        return when (detail) {
            is String -> detail.take(500)
            is JSONArray -> "请求参数不正确，请检查输入（$status）"
            else -> when (status) {
                401 -> "连接凭证已失效，请重新连接"
                413 -> "文件太大，单个文件限 5 MB"
                429 -> "请求过于频繁或服务商额度不足，请稍后再试"
                in 300..399 -> "后端返回重定向，请使用最终地址"
                else -> "后端请求失败（$status）"
            }
        }
    }
}
