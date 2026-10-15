package com.zhiyu.app.data.stream

import com.zhiyu.app.data.LocalMemoryStrictJson
import com.zhiyu.app.domain.ApiFailure
import com.zhiyu.app.domain.LocalMemoryCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

/** A single response, never reusable after finish/failure; callbacks contain only public choice-zero text. */
internal class DeepSeekSseDecoder(private val onDelta: suspend (String) -> Unit) {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    private val answer = StringBuilder()
    private val pending = StringBuilder()
    private var totalBytes = 0
    private var eventBytes = 0
    private var ignoreLf = false
    private var firstLine = true
    private var eventError = false
    private var stopped = false
    private var finished = false
    var isDone = false
        private set

    suspend fun accept(bytes: ByteArray) {
        if (finished || totalBytes.toLong() + bytes.size > MAX_RESPONSE) invalid()
        totalBytes += bytes.size
        bytes.forEachIndexed { index, byte ->
            if (index and 4095 == 0) currentCoroutineContext().ensureActive()
            if (byte == LF && ignoreLf) { ignoreLf = false; return@forEachIndexed }
            ignoreLf = false
            if (byte == LF || byte == CR) {
                val raw = line.toByteArray(); line.reset()
                consumeLine(raw)
                ignoreLf = byte == CR
            } else {
                if (line.size() >= MAX_EVENT) invalid()
                line.write(byte.toInt())
            }
        }
    }

    suspend fun finish(): String {
        currentCoroutineContext().ensureActive()
        if (finished || !isDone || !stopped || line.size() != 0 || data.isNotEmpty() || answer.isBlank()) invalid()
        finished = true
        return answer.toString()
    }

    private suspend fun consumeLine(raw: ByteArray) {
        val decoded = try { LocalMemoryCodec.utf8(raw) }
        catch (_: java.nio.charset.CharacterCodingException) { invalid() }
        val text = if (firstLine) decoded.removePrefix("\uFEFF") else decoded
        firstLine = false
        if (text.isEmpty()) {
            if (eventError) invalid()
            if (data.isNotEmpty()) consumeEvent(data.dropLast(1).toString())
            data.clear(); eventBytes = 0; eventError = false
            return
        }
        if (text.startsWith(":")) return
        val separator = text.indexOf(':')
        val field = if (separator < 0) text else text.substring(0, separator)
        val value = if (separator < 0) "" else text.substring(separator + 1).removePrefix(" ")
        if (field == "event" && value == "error") eventError = true
        if (field == "data") {
            eventBytes += raw.size + 1
            if (eventBytes > MAX_EVENT) invalid()
            data.append(value).append('\n')
        }
    }

    private suspend fun consumeEvent(text: String) {
        if (isDone) invalid()
        if (text == "[DONE]") {
            if (!stopped) invalid()
            flushPublicText(finishing = true)
            isDone = true
            return
        }
        val event = try { LocalMemoryStrictJson.parse(text) as? Map<*, *> ?: invalid() }
        catch (_: IllegalArgumentException) { invalid() }
        if (event.containsKey("error") || event.containsKey("error_code")) invalid()
        val choices = event["choices"] as? List<*> ?: invalid()
        val choiceZero = choices.map { it as? Map<*, *> ?: invalid() }.filter { it["index"] == 0.0 }
        if (choiceZero.size > 1) invalid()
        val choice = choiceZero.singleOrNull() ?: return
        val delta = choice["delta"] as? Map<*, *> ?: invalid()
        if (delta["role"] != null && delta["role"] != "assistant") invalid()
        if (delta["tool_calls"] != null || delta["function_call"] != null) invalid()
        val content = delta["content"]
        if (content != null && content !is String) invalid()
        if (content is String && content.isNotEmpty()) {
            if (stopped) invalid()
            pending.append(content)
            flushPublicText(finishing = false)
        }
        when (val reason = choice["finish_reason"]) {
            null -> Unit
            "stop" -> { if (stopped) invalid(); stopped = true }
            "length" -> throw ApiFailure(422, "模型输出预算已用完，未得到完整回答；请增加输出预算后重试", "provider_output_limit")
            else -> { if (reason !is String) invalid(); invalid() }
        }
    }

    /** Keep possible tag prefixes until resolved; private reasoning never crosses the callback boundary. */
    private suspend fun flushPublicText(finishing: Boolean) {
        val text = pending.toString()
        if (THINK_MARKERS.any { text.contains(it, ignoreCase = true) }) invalid()
        val hold = if (finishing) 0 else (1..minOf(text.length, 7)).filter { size ->
            THINK_MARKERS.any { it.startsWith(text.takeLast(size), ignoreCase = true) }
        }.maxOrNull() ?: 0
        val emit = text.dropLast(hold)
        if (emit.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            onDelta(emit)
            currentCoroutineContext().ensureActive()
            answer.append(emit)
        }
        pending.clear(); pending.append(text.takeLast(hold))
    }

    private fun invalid(): Nothing = throw ApiFailure(502, "AI 返回的流式回答不完整或格式无效，请重试", "provider_response_invalid")

    companion object {
        private const val MAX_RESPONSE = 2 * 1024 * 1024
        private const val MAX_EVENT = 64 * 1024
        private const val LF: Byte = 10
        private const val CR: Byte = 13
        private val THINK_MARKERS = listOf("<think>", "</think>")
    }
}
