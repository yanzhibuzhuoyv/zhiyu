package com.zhiyu.app.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.DigestInputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class LocalEncodedText(val compressed: ByteArray, val utf8Bytes: Int, val sha256: String)
data class LocalDecodedTail(val text: String, val startOffset: Int, val originalChars: Int)

/** Compression is lossless; neither summaries nor merged facts replace an original. */
object LocalMemoryCodec {
    const val MAX_RAW_BYTES = 5 * 1024 * 1024
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    fun bytes(text: String): ByteArray {
        val buffer = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text)) }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("原文包含无效Unicode字符，不能静默改写") }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    fun encode(text: String): LocalEncodedText {
        val bytes = bytes(text)
        require(bytes.size in 0..MAX_RAW_BYTES) { "原文长度超过本机保存范围" }
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(bytes) }
        return LocalEncodedText(output.toByteArray(), bytes.size, sha(bytes))
    }
    fun decode(compressed: ByteArray, expectedBytes: Int, expectedSha: String): String {
        require(expectedBytes in 0..MAX_RAW_BYTES && expectedSha.matches(Regex("[a-f0-9]{64}"))) { "本机原文校验信息无效" }
        val output = ByteArrayOutputStream(minOf(expectedBytes, 8192))
        try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= expectedBytes) { "本机原文大小校验失败" }
                    output.write(buffer, 0, count)
                }
            }
        } catch (_: java.io.IOException) { throw IllegalArgumentException("本机压缩原文损坏，不能读取") }
        val bytes = output.toByteArray()
        require(bytes.size == expectedBytes && sha(bytes) == expectedSha) { "本机原文完整性校验失败" }
        return utf8(bytes)
    }
    fun decodeSpan(compressed: ByteArray, expectedBytes: Int, expectedSha: String, start: Int, end: Int): String {
        require(expectedBytes in 1..MAX_RAW_BYTES && expectedSha.matches(Regex("[a-f0-9]{64}")) && start >= 0 && end > start && end - start <= 8192) { "原文节选校验信息无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        val inflater = InflaterInputStream(ByteArrayInputStream(compressed))
        var rawBytes = 0
        val checked = object : InputStream() {
            override fun read(): Int = inflater.read().also { if (it >= 0) { rawBytes++; require(rawBytes <= expectedBytes) } }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inflater.read(buffer, offset, length).also {
                if (it > 0) { rawBytes += it; require(rawBytes <= expectedBytes) }
            }
            override fun close() = inflater.close()
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val selected = StringBuilder(end - start)
        var position = 0
        try {
            InputStreamReader(DigestInputStream(checked, digest), decoder).use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val from = maxOf(0, start - position)
                    val until = minOf(count, end - position)
                    if (until > from) selected.append(buffer, from, until - from)
                    position += count
                }
            }
        } catch (_: java.io.IOException) { throw IllegalArgumentException("本机原文无法完整校验") }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        require(rawBytes == expectedBytes && actualSha == expectedSha && position >= end && selected.length == end - start) { "本机原文完整性或节选范围校验失败" }
        return selected.toString()
    }
    /** Verifies the full archive while retaining at most limit UTF16 characters in memory. */
    fun decodeTail(compressed: ByteArray, expectedBytes: Int, expectedSha: String, limit: Int): LocalDecodedTail {
        require(expectedBytes in 0..MAX_RAW_BYTES && expectedSha.matches(Regex("[a-f0-9]{64}")) && limit in 1..48000) { "原文节选校验信息无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        val inflater = InflaterInputStream(ByteArrayInputStream(compressed))
        var rawBytes = 0
        val checked = object : InputStream() {
            override fun read(): Int = inflater.read().also { if (it >= 0) { rawBytes++; require(rawBytes <= expectedBytes) } }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inflater.read(buffer, offset, length).also {
                if (it > 0) { rawBytes += it; require(rawBytes <= expectedBytes) }
            }
            override fun close() = inflater.close()
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val tail = StringBuilder(limit + 2048)
        var total = 0
        try {
            InputStreamReader(DigestInputStream(checked, digest), decoder).use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    total += count; tail.append(buffer, 0, count)
                    if (tail.length > limit) tail.delete(0, tail.length - limit)
                }
            }
        } catch (_: java.io.IOException) { throw IllegalArgumentException("本机原文无法完整校验") }
        require(rawBytes == expectedBytes && digest.digest().joinToString("") { "%02x".format(it) } == expectedSha) { "本机原文完整性校验失败" }
        if (tail.isNotEmpty() && Character.isLowSurrogate(tail[0])) tail.deleteCharAt(0)
        return LocalDecodedTail(tail.toString(), total - tail.length, total)
    }
}
