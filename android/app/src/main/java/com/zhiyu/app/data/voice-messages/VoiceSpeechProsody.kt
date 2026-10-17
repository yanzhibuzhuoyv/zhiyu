package com.zhiyu.app.data.voicemessages

import com.zhiyu.app.data.LocalVoiceRules

internal data class VoiceSpeechPart(val text: String, val pauseMillis: Int = 0)

/** Original text is never rewritten; only the audio derivative may contain bounded silence. */
internal object VoiceSpeechProsody {
    fun parts(text: String): List<VoiceSpeechPart> {
        val chunks = mutableListOf<String>()
        var pending = ""
        for (chunk in LocalVoiceRules.speechChunks(text)) {
            var start = 0
            chunk.forEachIndexed { index, character ->
                if (character in "，。！？；、!?;\n") {
                    chunks.add(pending + chunk.substring(start, index + 1)); pending = ""; start = index + 1
                }
            }
            pending += chunk.substring(start)
            if (pending.length >= 120) { chunks.add(pending); pending = "" }
        }
        if (pending.isNotEmpty()) chunks.add(pending)
        val spoken = mutableListOf<String>()
        var leading = ""
        chunks.forEach { chunk ->
            if (chunk.isBlank() || chunk.none(Char::isLetterOrDigit)) {
                if (spoken.isEmpty()) leading += chunk else spoken[spoken.lastIndex] += chunk
            } else { spoken.add(leading + chunk); leading = "" }
        }
        val bounded = spoken.flatMap { LocalVoiceRules.speechChunks(it) }.filter(String::isNotBlank)
        return bounded.mapIndexed { index, part ->
            val pause = if (index == bounded.lastIndex) 0 else if (part.endsWith('\n')) 260 else when (part.trimEnd().lastOrNull()) {
                '，', '、' -> 100
                '；', ';' -> 160
                '。', '！', '？', '!', '?' -> 220
                else -> 0
            }
            VoiceSpeechPart(part, pause)
        }
    }
}
