package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Independent capabilities. OCR/caption failures remain explicit; neither is stored as user text. */
class ChatPhotoAnalyzer(private val ocr: ChatPhotoOcr, private val vision: ChatPhotoVision) {
    suspend fun prepare(photo: ChatPhotoPayload, providerId: String, model: String): ChatPhotoAnalysis {
        if (ChatPhotoRules.nativeVision(providerId, model)) return ChatPhotoAnalysis(true, emptyList())
        return coroutineScope {
            val written = async { observe(photo, "ocr") { ocr.read(photo) } }
            val pictured = async { observe(photo, "vision") { vision.describe(photo) } }
            val results = listOf(written.await(), pictured.await())
            ChatPhotoAnalysis(false, results.mapNotNull { it.first }, results.mapNotNull { it.second }.toSet())
        }
    }
    private suspend fun observe(photo: ChatPhotoPayload, kind: String, action: suspend () -> String):
        Pair<ChatPhotoObservation?, String?> = try {
        val text = action()
        (text.takeIf(String::isNotBlank)?.let { ChatPhotoObservation(photo.originalSha256, kind, it) }) to null
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null to kind
    }
}
