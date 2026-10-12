package com.zhiyu.app.data

import android.content.Context
import android.net.Uri
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Originals stay in the checked media store; normalized bytes and observations are separate derivatives. */
class ChatPhotoStore(context: Context, private val processor: ChatPhotoImageProcessor = ChatPhotoImageProcessor()) {
    private val appContext = context.applicationContext
    private val media = LocalMediaStore(appContext)
    private val root = File(appContext.filesDir, "chat_photo_derivatives")
    private val draftPrefs = appContext.getSharedPreferences("zhiyu_chat_photo_draft", Context.MODE_PRIVATE)
    companion object { private val lock = Any() }

    fun import(uri: Uri): ChatImageDraft {
        ChatPhotoRules.documentUri(uri.toString())
        val original = appContext.contentResolver.openInputStream(uri)?.use(ChatPhotoRules::boundedBytes)
            ?: throw IllegalArgumentException("无法打开这张图片，请重新选择")
        return importBytes(original)
    }

    fun importBytes(original: ByteArray): ChatImageDraft = synchronized(lock) {
        require(original.size in 1..ChatPhotoRules.MAX_ORIGINAL_BYTES) { "请选择 64 MiB 以内的聊天图片" }
        val copy = original.copyOf()
        val normalized = processor.normalize(copy)
        val attachment = media.ingest("image", copy, sourceLabel = "你发送的图片", purpose = "chat")
        require(attachment.sha256 == normalized.originalSha256)
        persistNormalized(attachment, normalized)
        ChatImageDraft(attachment)
    }

    fun payload(attachment: MediaAttachment): ChatPhotoPayload = synchronized(lock) {
        checkAttachment(attachment)
        // Also check the retained original every time; a derivative must never conceal missing/corrupt history.
        val original = media.download(attachment.id, attachment.sha256)
        val record = File(root, "${attachment.id}.json")
        if (!record.isFile) return@synchronized processor.normalize(original).also { persistNormalized(attachment, it) }
        require(record.length() in 1..4096L) { "聊天图片标注损坏" }
        val value = JSONObject(record.readText(Charsets.UTF_8))
        require(value.getString("original_sha256") == attachment.sha256 && value.getString("id") == attachment.id)
        val normalizedSha = value.getString("normalized_sha256")
        ChatPhotoRules.digest(normalizedSha)
        val blob = File(File(root, "normalized"), "$normalizedSha.bin")
        require(blob.isFile && blob.length() in 1..ChatPhotoRules.MAX_UPLOAD_BYTES.toLong()) { "聊天图片副本缺失或过大" }
        ChatPhotoPayload(attachment.sha256, normalizedSha, value.getString("mime"), blob.readBytes())
    }

    fun saveDraft(draft: ChatImageDraft) = synchronized(lock) {
        checkAttachment(draft.attachment)
        check(draftPrefs.edit().putString("id", draft.attachment.id).putString("sha256", draft.attachment.sha256).commit()) {
            "无法保存图片草稿，请重试"
        }
    }

    fun readDraft(): ChatImageDraft? = synchronized(lock) {
        val id = draftPrefs.getString("id", null) ?: return@synchronized null
        MediaRules.path(id)
        val sha = draftPrefs.getString("sha256", null) ?: throw IllegalStateException("图片草稿不完整，请重新选择")
        ChatPhotoRules.digest(sha)
        val asset = media.details(id).attachment
        require(asset.sha256 == sha)
        checkAttachment(asset)
        media.download(id, sha)
        ChatImageDraft(asset)
    }

    fun clearDraftIf(id: String? = null) = synchronized(lock) {
        if (id != null && draftPrefs.getString("id", null) != id) return@synchronized
        check(draftPrefs.edit().remove("id").remove("sha256").commit()) { "无法更新图片草稿" }
    }

    fun observations(attachment: MediaAttachment): List<ChatPhotoObservation> = synchronized(lock) {
        checkAttachment(attachment)
        val file = observationFile(attachment.sha256)
        if (!file.isFile) return@synchronized emptyList()
        require(file.length() in 1..128_000L) { "图片观察标注损坏" }
        val values = JSONArray(file.readText(Charsets.UTF_8))
        require(values.length() <= 2)
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            require(!value.getBoolean("confirmed") && value.getString("original_sha256") == attachment.sha256)
            ChatPhotoObservation(attachment.sha256, value.getString("kind"), value.getString("text"))
        }.also { ChatPhotoAnalysis(false, it) }
    }

    fun saveObservations(attachment: MediaAttachment, observations: List<ChatPhotoObservation>) = synchronized(lock) {
        checkAttachment(attachment)
        ChatPhotoAnalysis(false, observations)
        require(observations.all { it.originalSha256 == attachment.sha256 }) { "图片观察不属于这张原图" }
        val values = JSONArray().also { array -> observations.forEach {
            array.put(JSONObject().put("original_sha256", it.originalSha256).put("kind", it.kind)
                .put("text", it.text).put("confirmed", false))
        } }
        LocalMediaStore.write(observationFile(attachment.sha256), LocalMemoryCodec.bytes(values.toString()))
    }

    private fun checkAttachment(attachment: MediaAttachment) {
        MediaRules.check(attachment)
        val saved = media.details(attachment.id)
        require(saved.purpose == "chat" && saved.attachment == attachment && attachment.kind == "image") { "请重新选择要发送的聊天图片" }
    }

    private fun persistNormalized(attachment: MediaAttachment, payload: ChatPhotoPayload) {
        LocalMediaStore.write(File(File(root, "normalized"), "${payload.normalizedSha256}.bin"), payload.bytes)
        val value = JSONObject().put("id", attachment.id).put("original_sha256", attachment.sha256)
            .put("normalized_sha256", payload.normalizedSha256).put("mime", payload.mimeType)
        LocalMediaStore.write(File(root, "${attachment.id}.json"), LocalMemoryCodec.bytes(value.toString()))
    }
    private fun observationFile(sha: String): File {
        ChatPhotoRules.digest(sha)
        return File(File(root, "observations"), "$sha.json")
    }
}
