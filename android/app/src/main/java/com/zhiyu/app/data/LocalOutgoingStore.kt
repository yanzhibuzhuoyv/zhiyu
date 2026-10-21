package com.zhiyu.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.zhiyu.app.domain.*
import org.json.JSONObject

/** Durable, bounded outbox; no Key, session token, or model response is copied into it. */
internal class LocalOutgoingStore(private val archive: LocalChatDatabase, private val owner: String) {
    fun save(draft: OutgoingDraft) = archive.atomic {
        checkDraft(draft)
        archive.conversation(owner, draft.chatId)
        val encoded = LocalMemoryCodec.encode(draft.content)
        val metadata = metadata(draft).toString()
        val previous = archive.readableDatabase.rawQuery(
            "SELECT sha256,metadata,owner FROM outgoing_drafts WHERE request_id=?", arrayOf(draft.id)).use {
            if (!it.moveToFirst()) null else Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        if (previous != null) {
            check(previous == Triple(encoded.sha256, metadata, owner)) { "待发消息标识已用于另一条原话" }
        } else {
            val count = archive.readableDatabase.rawQuery("SELECT COUNT(*) FROM outgoing_drafts WHERE owner=?", arrayOf(owner)).use {
                check(it.moveToFirst()); it.getInt(0)
            }
            check(count < OutgoingQueueRules.CAPACITY) { "待发送的消息有点多，请先处理已有消息" }
            archive.writableDatabase.insertOrThrow("outgoing_drafts", null, ContentValues().apply {
                put("request_id", draft.id); put("owner", owner); put("conversation_id", draft.chatId)
                put("payload", encoded.compressed); put("raw_bytes", encoded.utf8Bytes); put("sha256", encoded.sha256)
                put("metadata", metadata); put("image_id", draft.image?.attachment?.id); put("voice_id", draft.voiceDraftId)
            })
        }
        Unit
    }

    fun restore(): List<OutgoingDraft> = archive.atomic {
        val drafts = archive.readableDatabase.rawQuery(
            "SELECT request_id,conversation_id,payload,raw_bytes,sha256,metadata FROM outgoing_drafts WHERE owner=? ORDER BY position",
            arrayOf(owner)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(size < OutgoingQueueRules.CAPACITY) { "待发消息记录超过容量，原文已保留" }
                    val raw = cursor.getString(5)
                    require(raw.length <= 16384) { "待发消息标注无效，原文已保留" }
                    val json = JSONObject(raw)
                    add(OutgoingDraft(cursor.getString(0), cursor.getString(1), 0,
                        json.getString("server"), json.getString("provider"), json.getString("model"),
                        LocalMemoryCodec.decode(cursor.getBlob(2), cursor.getInt(3), cursor.getString(4)),
                        json.optJSONObject("image")?.let { ChatImageDraft(it.mediaAttachment()) },
                        json.optionalString("voice"), textBatchId = json.optionalString("batch")).also(::checkDraft))
                }
            }
        }
        drafts.mapNotNull { draft ->
            val user = archive.proactiveResult(owner, draft.chatId, "outgoing-${draft.id}")
            // Membership points at the LAST original; the reply is keyed by the FIRST input.
            val batchEnd = archive.proactiveResult(owner, draft.chatId, "outgoing-member-${draft.id}")
            val firstRequest = if (batchEnd == null) draft.id else {
                archive.readableDatabase.rawQuery("SELECT r.request_id FROM proactive_requests b " +
                    "JOIN proactive_requests r ON r.owner=b.owner AND r.conversation_id=b.conversation_id " +
                    "AND r.request_id='outgoing-' || substr(b.request_id,17) JOIN messages m ON m.id=r.message_id " +
                    "WHERE b.owner=? AND b.conversation_id=? AND b.message_id=? AND b.request_id LIKE 'outgoing-member-%' " +
                    "AND length(b.request_id)=48 ORDER BY m.seq LIMIT 1", arrayOf(owner, draft.chatId, batchEnd.id)).use {
                    if (it.moveToFirst()) it.getString(0).removePrefix("outgoing-") else null
                }
            }
            val reply = firstRequest?.let { archive.proactiveResult(owner, draft.chatId, "outgoing-reply-$it") }
            when {
                reply != null -> { remove(draft.id); null }
                user != null -> draft.copy(phase = OutgoingPhase.SUBMITTED_NO_REPLY, submittedMessageId = user.id,
                    problem = "这句话已保存，上次回复没有等到；不用重复发送")
                else -> draft.copy(phase = OutgoingPhase.FAILED, problem = "上次还没发出，原话已保留，可以点重试")
            }
        }
    }

    fun remove(requestId: String) {
        MediaRules.path(requestId)
        archive.writableDatabase.delete("outgoing_drafts", "owner=? AND request_id=?", arrayOf(owner, requestId))
    }

    private fun checkDraft(draft: OutgoingDraft) {
        MediaRules.path(draft.id); MediaRules.path(draft.chatId)
        require(draft.content.length <= 16000 && (draft.content.isNotBlank() || draft.image != null)) { "待发原文无效" }
        require(draft.serverUrl == LocalChatRepository.LOCAL_URL && draft.providerId.length in 1..128 && draft.model.length in 1..256)
        require(draft.image == null || draft.voiceDraftId == null)
        draft.image?.let { MediaRules.check(it.attachment); require(it.attachment.kind == "image") }
        draft.voiceDraftId?.let(MediaRules::path); draft.textBatchId?.let(MediaRules::path)
        require(draft.textBatchId == null || draft.image == null && draft.voiceDraftId == null)
    }

    private fun metadata(draft: OutgoingDraft) = JSONObject().put("server", draft.serverUrl)
        .put("provider", draft.providerId).put("model", draft.model)
        .put("voice", draft.voiceDraftId ?: JSONObject.NULL).put("batch", draft.textBatchId ?: JSONObject.NULL)
        .put("image", draft.image?.attachment?.let { image -> JSONObject().put("id", image.id).put("kind", image.kind)
            .put("url", image.url).put("mime_type", image.mimeType).put("sha256", image.sha256)
            .put("day_id", image.dayId ?: JSONObject.NULL).put("visual_version", image.visualVersion).put("source_label", image.sourceLabel) } ?: JSONObject.NULL)

    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)

    companion object {
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS outgoing_drafts (position INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE, " +
                "owner TEXT NOT NULL, conversation_id TEXT NOT NULL REFERENCES conversations(id), payload BLOB NOT NULL, raw_bytes INTEGER NOT NULL, " +
                "sha256 TEXT NOT NULL, metadata TEXT NOT NULL, image_id TEXT, voice_id TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS outgoing_owner ON outgoing_drafts(owner,position)")
        }
    }
}
