package com.zhiyu.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class LocalPromptContext(val messages: List<Message>, val completeMessageIds: Set<String>)

/** Private device archive. Original bodies are losslessly compressed and individually checked. */
class LocalChatDatabase(context: Context, name: String = "zhiyu_local_v1.sqlite3") : SQLiteOpenHelper(context.applicationContext, name, null, 3) {
    private val media = LocalMediaStore(context)
    val isMainArchive = name == "zhiyu_local_v1.sqlite3"
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, owner TEXT NOT NULL UNIQUE, model TEXT NOT NULL, profile TEXT, use_rag INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, owner TEXT NOT NULL, conversation_id TEXT NOT NULL REFERENCES conversations(id), role TEXT NOT NULL CHECK(role IN ('user','assistant')), payload BLOB NOT NULL, raw_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at TEXT NOT NULL, origin TEXT NOT NULL, sources TEXT NOT NULL, attachments TEXT NOT NULL, tier TEXT NOT NULL DEFAULT 'mid', index_state TEXT NOT NULL DEFAULT 'active', revision INTEGER NOT NULL DEFAULT 0, manual_tier TEXT)")
        db.execSQL("CREATE INDEX messages_owner_page ON messages(owner,conversation_id,seq)")
        db.execSQL("CREATE TABLE documents (id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, chunks INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE document_parts (document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, part_index INTEGER NOT NULL, payload BLOB NOT NULL, raw_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(document_id,part_index))")
        db.execSQL("CREATE VIRTUAL TABLE memory_fts USING fts4(body_tokens,owner,message_id,document_id,part_index,start_offset,end_offset,original_chars,notindexed=owner,notindexed=message_id,notindexed=document_id,notindexed=part_index,notindexed=start_offset,notindexed=end_offset,notindexed=original_chars,tokenize=unicode61)")
        db.execSQL("CREATE TABLE proactive_requests (request_id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL REFERENCES messages(id))")
        LocalSemanticStore.createSchema(db)
        LocalMemoryRecognitionSchema.create(db)
        createColdIndex(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "本机聊天库需要明确迁移，不能清空原文" }
        LocalSemanticStore.createSchema(db)
        LocalMemoryRecognitionSchema.create(db)
        createColdIndex(db)
    }
    private fun createColdIndex(db:SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memory_cold_fts USING fts4(body_tokens,owner,message_id,document_id,part_index,start_offset,end_offset,original_chars,notindexed=owner,notindexed=message_id,notindexed=document_id,notindexed=part_index,notindexed=start_offset,notindexed=end_offset,notindexed=original_chars,tokenize=unicode61)")
    }

    fun markExternalHistoryPending() { writableDatabase.insertWithOnConflict("local_meta", null,
        values("key" to "external_history_pending", "value" to "1"), SQLiteDatabase.CONFLICT_IGNORE) }
    fun externalHistoryPending(): Boolean = readableDatabase.rawQuery("SELECT value FROM local_meta WHERE key=?", arrayOf("external_history_pending")).use {
        it.moveToFirst() && it.getString(0) == "1"
    }
    /** All owners and dates protect original message media; malformed records stop cache cleanup. */
    fun pinnedMediaIds(): Set<String> = readableDatabase.rawQuery("SELECT attachments FROM messages", null).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0)
                require(raw.length <= 16 * 1024) { "聊天图片标注损坏，暂不整理缓存" }
                val parsed = AgnesImageJson.parse(raw) as? List<*> ?: throw IllegalArgumentException("聊天图片标注损坏")
                require(parsed.size <= 4 && parsed.all { it is Map<*, *> }) { "聊天图片标注损坏" }
                JSONArray(raw).mapObjects { it.mediaAttachment() }.forEach { add(MediaRules.check(it).id) }
            }
        }
    }
    /** Exact metadata evidence, independent of recent-page limits and compressed original bodies. */
    fun hasUserMediaAttachment(owner: String, attachmentId: String): Boolean {
        require(owner.isNotBlank() && owner.length <= 256) { "聊天归属无效" }
        LocalMemoryCodec.bytes(owner); MediaRules.path(attachmentId)
        return readableDatabase.rawQuery("SELECT attachments FROM messages WHERE owner=? AND role='user' AND instr(attachments,?)>0 ORDER BY seq DESC",
            arrayOf(owner, attachmentId)).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0)
                require(raw.length <= 16 * 1024) { "聊天图片标注损坏" }
                val parsed = AgnesImageJson.parse(raw) as? List<*> ?: throw IllegalArgumentException("聊天图片标注损坏")
                require(parsed.size <= 4 && parsed.all { it is Map<*, *> }) { "聊天图片标注损坏" }
                if (JSONArray(raw).mapObjects { MediaRules.check(it.mediaAttachment()) }.any { it.id == attachmentId }) return@use true
            }
            false
        }
    }
    fun ensureConversation(owner: String, model: String, profile: CompanionProfile?): Conversation = transaction { db ->
        findConversation(owner)?.let { existing ->
            check(existing.model == model) { "本机聊天绑定与模型不一致" }
            return@transaction existing
        }
        val now = Instant.now().toString()
        val id = identifier()
        db.insertOrThrow("conversations", null, values("id" to id, "owner" to owner, "model" to model,
            "profile" to profile?.json()?.toString(), "use_rag" to 1, "created_at" to now, "updated_at" to now))
        conversation(owner, id)
    }
    fun findConversation(owner: String): Conversation? = readableDatabase.rawQuery("SELECT * FROM conversations WHERE owner=?", arrayOf(owner)).use {
        if (it.moveToFirst()) it.conversation() else null
    }
    fun conversation(owner: String, id: String): Conversation = readableDatabase.rawQuery("SELECT * FROM conversations WHERE owner=? AND id=?", arrayOf(owner, id)).use {
        if (!it.moveToFirst()) throw ApiFailure(404, "这段本机聊天不存在", "local_conversation_missing")
        it.conversation()
    }
    fun changeProfile(owner: String, id: String, profile: CompanionProfile?): Conversation {
        conversation(owner, id)
        writableDatabase.update("conversations", values("profile" to profile?.json()?.toString(), "updated_at" to Instant.now().toString()), "owner=? AND id=?", arrayOf(owner, id))
        return conversation(owner, id)
    }
    fun changeRag(owner: String, id: String, enabled: Boolean): Conversation {
        conversation(owner, id)
        writableDatabase.update("conversations", values("use_rag" to if (enabled) 1 else 0), "owner=? AND id=?", arrayOf(owner, id))
        return conversation(owner, id)
    }
    fun append(owner: String, id: String, role: String, content: String, origin: String = "reply", sources: List<Source> = emptyList(),
               attachments: List<MediaAttachment> = emptyList(), requestId: String? = null): Message {
        if (attachments.isEmpty()) return appendChecked(owner,id,role,content,origin,sources,attachments,requestId)
        return synchronized(LocalMediaStore.lock) {
            require(attachments.size <= 4)
            attachments.forEach { asset -> MediaRules.check(asset); media.download(asset.id,asset.sha256) }
            appendChecked(owner,id,role,content,origin,sources,attachments,requestId)
        }
    }
    private fun appendChecked(owner: String, id: String, role: String, content: String, origin: String,
        sources: List<Source>, attachments: List<MediaAttachment>, requestId: String?): Message = transaction { db ->
        require(role in setOf("user", "assistant") && (content.isNotBlank() ||
            role == "user" && attachments.size == 1 && attachments.first().kind == "image")) { "聊天原文无效" }
        require(attachments.size <= 4); attachments.forEach { MediaRules.check(it) }
        conversation(owner, id)
        requestId?.let { proactiveResult(owner, id, it)?.let { existing -> return@transaction existing } }
        val encoded = LocalMemoryCodec.encode(content)
        val message = Message(identifier(), role, content, sources, Instant.now().toString(), attachments, origin)
        db.insertOrThrow("messages", null, values("id" to message.id, "owner" to owner, "conversation_id" to id, "role" to role,
            "payload" to encoded.compressed, "raw_bytes" to encoded.utf8Bytes, "sha256" to encoded.sha256,
            "created_at" to message.createdAt, "origin" to origin, "sources" to sourceJson(sources), "attachments" to mediaJson(attachments)))
        indexMessage(db, owner, message.id, content)
        requestId?.let { db.insertOrThrow("proactive_requests", null, values("request_id" to it, "owner" to owner, "conversation_id" to id, "message_id" to message.id)) }
        db.update("conversations", values("updated_at" to message.createdAt), "owner=? AND id=?", arrayOf(owner, id))
        message
    }
    fun history(owner: String, id: String, before: String? = null, limit: Int = 100): MessagePage {
        require(limit in 1..100)
        conversation(owner, id)
        val beforeSeq = before?.let { sequence(owner, id, it) }
        val args = mutableListOf(owner, id)
        beforeSeq?.let { args.add(it.toString()) }
        val rows = readableDatabase.rawQuery("SELECT id,role,raw_bytes,sha256,created_at,origin,sources,attachments FROM messages WHERE owner=? AND conversation_id=?${if (beforeSeq == null) "" else " AND seq<?"} ORDER BY seq DESC LIMIT ${limit + 1}", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.metadata()) }
        }
        val selected = mutableListOf<Message>()
        var usedBytes = 0L
        for (row in rows.take(limit)) {
            if (selected.isNotEmpty() && usedBytes + row.rawBytes > 2 * 1024 * 1024) break
            selected.add(original(owner, id, row)); usedBytes += row.rawBytes
        }
        selected.reverse()
        val more = rows.size > selected.size
        return MessagePage(selected, more, if (more) selected.firstOrNull()?.id else null)
    }
    /** Model context never loads a hundred complete long bodies before applying its budget. */
    fun promptHistory(owner: String, id: String, budget: Int = 48000): List<Message> = promptContext(owner, id, budget).messages
    fun promptContext(owner: String, id: String, budget: Int = 48000): LocalPromptContext {
        require(budget in 128..48000)
        conversation(owner, id)
        val rows = readableDatabase.rawQuery("SELECT id,role,raw_bytes,sha256,created_at,origin,sources,attachments FROM messages WHERE owner=? AND conversation_id=? ORDER BY seq DESC LIMIT 40", arrayOf(owner, id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.metadata()) }
        }
        var used = 0
        val selected = mutableListOf<Message>()
        val complete = mutableSetOf<String>()
        for (row in rows) {
            val available = budget - used
            if (available < 160) break
            val decoded = LocalMemoryCodec.decodeTail(payload(owner, id, row.message.id), row.rawBytes, row.sha,
                minOf(48000, available))
            val content = if (decoded.startOffset == 0) decoded.text else {
                var start = decoded.text.length - minOf(8192, available - 150)
                if (Character.isLowSurrogate(decoded.text[start])) start++
                "[连续原文节选；作者=${row.message.role}；消息=${row.message.id}；UTF16范围=${decoded.startOffset + start}..${decoded.originalChars}]\n${decoded.text.substring(start)}"
            }
            if (content.length > available) break
            selected.add(row.message.copy(content = content)); used += content.length
            if (decoded.startOffset == 0) complete.add(row.message.id)
        }
        return LocalPromptContext(selected.asReversed(), complete.toSet())
    }
    fun mediaHistory(owner: String, id: String): List<Message> {
        conversation(owner, id)
        return readableDatabase.rawQuery("SELECT id,role,created_at,origin,sources,attachments,raw_bytes,sha256 FROM messages WHERE owner=? AND conversation_id=? ORDER BY seq DESC LIMIT 100", arrayOf(owner, id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.metadata().message) }.asReversed()
        }
    }
    fun recognitionOriginal(owner: String, id: String, messageId: String): LocalMemoryOriginal? = readableDatabase.rawQuery(
        "SELECT role,created_at,sha256,revision,index_state,manual_tier,raw_bytes FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner,id,messageId)).use {
        if (!it.moveToFirst()) null else LocalMemoryOriginal(owner,id,messageId,it.getString(0),it.getString(1),it.getString(2),it.getInt(3),
            LocalMemoryCodec.decode(payload(owner,id,messageId),it.getInt(6),it.getString(2)),it.getString(4),if (it.isNull(5)) null else it.getString(5))
    }
    fun semanticRecall(owner: String, id: String, hit: LocalSemanticHit): LocalRecall = readableDatabase.rawQuery(
        "SELECT raw_bytes,sha256 FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner,id,hit.messageId)).use {
        check(it.moveToFirst() && it.getString(1) == hit.rawSha) { "语义来源原文校验不一致" }
        val end = minOf(hit.end,hit.start + 8192)
        val text = LocalMemoryCodec.decodeSpan(payload(owner,id,hit.messageId),it.getInt(0),it.getString(1),hit.start,end)
        val safeText = if (Character.isHighSurrogate(text.last())) text.dropLast(1) else text
        LocalRecall(hit.messageId,hit.role,safeText,hit.createdAt,hit.tier,hit.score,hit.start,hit.start + safeText.length,hit.originalChars)
    }
    fun proactiveResult(owner: String, id: String, requestId: String): Message? = readableDatabase.rawQuery(
        "SELECT m.* FROM proactive_requests p JOIN messages m ON m.id=p.message_id WHERE p.owner=? AND p.conversation_id=? AND p.request_id=?",
        arrayOf(owner, id, requestId)).use { if (it.moveToFirst()) it.message() else null }
    fun proactivePolicyHistory(owner: String, id: String): List<Message> {
        conversation(owner, id)
        val since = Instant.now().minusSeconds(3 * 86400).toString()
        return readableDatabase.rawQuery("SELECT id,role,created_at,origin FROM messages WHERE owner=? AND conversation_id=? AND (created_at>=? OR seq>=(SELECT COALESCE(MAX(seq),0) FROM messages WHERE owner=? AND conversation_id=? AND role='user') OR seq=(SELECT MAX(seq) FROM messages WHERE owner=? AND conversation_id=? AND origin='reply')) ORDER BY seq",
            arrayOf(owner, id, since, owner, id, owner, id)).use { cursor -> buildList {
                while (cursor.moveToNext()) add(Message(cursor.getString(0), cursor.getString(1), "", emptyList(), cursor.getString(2), origin = cursor.getString(3)))
            } }
    }
    fun recall(owner: String, id: String, query: String, exclude: Set<String> = emptySet(), budget: Int = 12000, state: String = "active"): List<LocalRecall> {
        require(state in setOf("active","cold"))
        val ftsTable = if (state == "cold") "memory_cold_fts" else "memory_fts"
        val match = LocalMemoryRules.matchQuery(query)
        if (match.isBlank()) return emptyList()
        val terms = LocalMemoryRules.queryTerms(query)
        val relevance = terms.joinToString("+") { "CASE WHEN instr(' '||f.body_tokens||' ',?)>0 THEN 1 ELSE 0 END" }
        val (sql, args) = recallCandidateQuery(owner, id, query, exclude,state)
        // Aggregate only the numeric candidate score. Texts and original spans remain separate.
        val candidates = readableDatabase.rawQuery(sql, args).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val messageId = cursor.getString(0)
                add(LocalRecall(messageId, cursor.getString(1), "", cursor.getString(2), cursor.getString(3),
                    cursor.getDouble(4)))
            }
        } }
        var used = 0
        return candidates.mapNotNull { metadata ->
            val spanArgs = terms.map { " $it " } + listOf(match, owner, metadata.messageId)
            val candidate = readableDatabase.rawQuery("SELECT f.start_offset,f.end_offset,f.original_chars,($relevance) AS relevance FROM $ftsTable f WHERE f.body_tokens MATCH ? AND f.owner=? AND f.message_id=? ORDER BY relevance DESC,CAST(f.part_index AS INTEGER) DESC LIMIT 1", spanArgs.toTypedArray()).use {
                check(it.moveToFirst()) { "原文候选片段丢失" }
                metadata.copy(startOffset = it.getString(0).toInt(), endOffset = it.getString(1).toInt(), originalChars = it.getString(2).toInt())
            }
            val width = candidate.endOffset - candidate.startOffset
            if (candidate.score <= 0 || width <= 0 || used + width > budget) return@mapNotNull null
            val text = readableDatabase.rawQuery("SELECT raw_bytes,sha256 FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner, id, candidate.messageId)).use {
                check(it.moveToFirst()) { "原文索引来源丢失" }
                LocalMemoryCodec.decodeSpan(payload(owner, id, candidate.messageId), it.getInt(0), it.getString(1), candidate.startOffset, candidate.endOffset)
            }
            used += text.length
            candidate.copy(text = text)
        }
    }
    /** Diagnostics use exactly the production candidate query, without reading original payloads. */
    internal fun recallCandidatePlan(owner: String, id: String, query: String, exclude: Set<String> = emptySet()): List<String> {
        if (LocalMemoryRules.matchQuery(query).isBlank()) return emptyList()
        val (sql, args) = recallCandidateQuery(owner, id, query, exclude)
        return readableDatabase.rawQuery("EXPLAIN QUERY PLAN $sql", args).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.getString(3))
        } }
    }
    private fun recallCandidateQuery(owner: String, id: String, query: String, exclude: Set<String>, state:String="active"): Pair<String, Array<String>> {
        require(exclude.size <= 128) { "近期消息排除范围过大" }
        val terms = LocalMemoryRules.queryTerms(query)
        val relevance = terms.joinToString("+") { "CASE WHEN instr(' '||f.body_tokens||' ',?)>0 THEN 1 ELSE 0 END" }
        val exclusions = if (exclude.isEmpty()) "" else " AND m.id NOT IN (${exclude.joinToString(",") { "?" }})"
        val args = terms.map { " $it " } + listOf(LocalMemoryRules.matchQuery(query), owner, id) + exclude.toList()
        return recallCandidateSql(relevance, exclusions,state) to args.toTypedArray()
    }
    // MATCH must drive the join. An owner-first loop would reopen FTS once per archived message.
    private fun recallCandidateSql(relevance: String, exclusions: String,state:String):String {
        val ftsTable = if (state == "cold") "memory_cold_fts" else "memory_fts"
        return "SELECT m.id,m.role,m.created_at,m.tier,MAX($relevance) AS relevance FROM $ftsTable f CROSS JOIN messages m WHERE m.id=f.message_id AND f.body_tokens MATCH ? AND m.owner=? AND m.conversation_id=? AND m.index_state='$state'$exclusions GROUP BY m.id,m.role,m.created_at,m.tier,m.seq ORDER BY relevance DESC,CASE m.role WHEN 'user' THEN 0 ELSE 1 END,CASE m.tier WHEN 'permanent' THEN 0 WHEN 'long' THEN 1 ELSE 2 END,m.seq DESC LIMIT 8"
    }
    fun documentSources(owner: String, query: String): List<Source> {
        val match = LocalMemoryRules.matchQuery(query)
        if (match.isBlank()) return emptyList()
        return readableDatabase.rawQuery("SELECT DISTINCT d.id,d.name,p.payload,p.raw_bytes,p.sha256 FROM memory_fts f JOIN documents d ON d.id=f.document_id JOIN document_parts p ON p.document_id=d.id AND p.part_index=f.part_index WHERE f.body_tokens MATCH ? AND d.owner=? LIMIT 4", arrayOf(match, owner)).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Source(cursor.getString(0), cursor.getString(1), LocalMemoryCodec.decode(cursor.getBlob(2), cursor.getInt(3), cursor.getString(4)), 1.0))
        } }
    }
    fun documents(owner: String): List<Document> = readableDatabase.rawQuery("SELECT id,name,size,chunks,created_at FROM documents WHERE owner=? ORDER BY created_at DESC", arrayOf(owner)).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Document(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3), cursor.getString(4)))
    } }
    fun importDocument(owner: String, name: String, text: String): Document = transaction { db ->
        val raw = LocalMemoryCodec.bytes(text)
        require(raw.size in 1..ConnectionRules.MAX_FILE_BYTES && text.isNotBlank()) { "文件内容为空或超过5 MB" }
        val parts = LocalMemoryRules.parts(text)
        val document = Document(identifier(), name, raw.size.toLong(), parts.size, Instant.now().toString())
        db.insertOrThrow("documents", null, values("id" to document.id, "owner" to owner, "name" to name, "size" to raw.size,
            "chunks" to parts.size, "sha256" to LocalMemoryCodec.sha(raw), "created_at" to document.createdAt))
        parts.forEachIndexed { index, part ->
            val encoded = LocalMemoryCodec.encode(part.text)
            db.insertOrThrow("document_parts", null, values("document_id" to document.id, "part_index" to index,
                "payload" to encoded.compressed, "raw_bytes" to encoded.utf8Bytes, "sha256" to encoded.sha256))
            indexPart(db, owner, "", document.id, index, part.text)
        }
        document
    }
    fun deleteDocument(owner: String, id: String) = transaction { db ->
        val removed = db.delete("documents", "owner=? AND id=?", arrayOf(owner, id))
        if (removed == 0) throw ApiFailure(404, "这份本机文件不存在", "local_document_missing")
        db.delete("memory_fts", "owner=? AND document_id=?", arrayOf(owner, id))
    }
    fun memory(owner: String, id: String, before: String?): MemoryPage {
        val page = history(owner, id, before, 50)
        val scheduled = readableDatabase.rawQuery("SELECT EXISTS(SELECT 1 FROM native_memory_jobs WHERE owner=? AND conversation_id=? AND version=?)",arrayOf(owner,id,LocalMemoryRecognitionRules.VERSION)).use {
            it.moveToFirst() && it.getInt(0) != 0
        }
        return MemoryPage(page.messages.asReversed().map { memoryEntry(owner, id, it.id) }, page.hasMore, page.nextBefore, scheduled)
    }
    fun memoryEntry(owner: String, id: String, messageId: String): MemoryEntry = readableDatabase.rawQuery("SELECT id,role,raw_bytes,sha256,created_at,origin,sources,attachments,tier,index_state,revision,manual_tier FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner, id, messageId)).use { cursor ->
        if (!cursor.moveToFirst()) throw ApiFailure(404, "这句原文不存在", "local_message_missing")
        val message = original(owner,id,cursor.metadata())
        val count = message.content.codePointCount(0, message.content.length)
        val recognition = recognitionStatus(owner,id,message,cursor.text("sha256"))
        MemoryEntry(message.id, message.role, message.content.substring(0, message.content.offsetByCodePoints(0, minOf(count, 768))), message.createdAt,
            count, cursor.text("tier"), cursor.text("index_state"), cursor.number("revision"), cursor.text("tier") == "permanent" || annotationPinned(owner,id,message,cursor.text("sha256")), "完整原文独立保存在本机", cursor.nullText("manual_tier"), false,
            if (recognition == "unavailable") "none" else "semantic",recognition)
    }
    private fun recognitionStatus(owner:String,id:String,message:Message,sha:String):String = readableDatabase.rawQuery(
        "SELECT status FROM native_memory_jobs WHERE owner=? AND conversation_id=? AND message_id=? AND source_sha=? AND source_role=? AND source_created_at=? AND version=?",
        arrayOf(owner,id,message.id,sha,message.role,message.createdAt,LocalMemoryRecognitionRules.VERSION)).use {
        if (!it.moveToFirst()) "unavailable" else it.getString(0).also { status ->
            check(status in setOf("pending","running","partial","ok","failed","waiting_auth")) { "本机记忆标注状态无法识别" }
        }
    }
    private fun annotationPinned(owner:String,id:String,message:Message,sha:String):Boolean = readableDatabase.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM native_memory_annotations WHERE owner=? AND conversation_id=? AND message_id=? AND source_sha=? AND role=? AND source_created_at=? AND validity='current' AND pinned=1)",
        arrayOf(owner,id,message.id,sha,message.role,message.createdAt)).use { it.moveToFirst() && it.getInt(0) != 0 }
    fun changeMemory(owner: String, id: String, messageId: String, change: MemoryChange): MemoryEntry = transaction { db ->
        val current = memoryEntry(owner, id, messageId)
        MemoryRules.change(current, change.tier, change.indexState, change.confirmIndependent)
        if (current.revision != change.expectedRevision) throw ApiFailure(409, "这句原文的设置已更新，请刷新后重试", "local_revision_conflict")
        val tier = if (change.tier == "auto") "mid" else change.tier ?: current.tier
        val state = change.indexState ?: current.indexState
        db.update("messages", values("tier" to tier, "index_state" to state, "revision" to current.revision + 1,
            "manual_tier" to if (change.tier == "auto") null else change.tier ?: current.manualTier), "owner=? AND id=?", arrayOf(owner, messageId))
        db.delete("memory_fts", "owner=? AND message_id=?", arrayOf(owner, messageId))
        db.delete("memory_cold_fts", "owner=? AND message_id=?", arrayOf(owner, messageId))
        val text = recognitionOriginal(owner,id,messageId)?.text ?: throw IllegalStateException("原文来源丢失")
        indexMessage(db, owner, messageId, text,if (state == "cold") "memory_cold_fts" else "memory_fts")
        memoryEntry(owner, id, messageId)
    }
    fun ensureColdIndex(owner:String,id:String,messageId:String,expectedRevision:Int,text:String) = transaction { db ->
        val current = db.rawQuery("SELECT revision,index_state,sha256 FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner,id,messageId)).use {
            it.moveToFirst() && it.getInt(0) == expectedRevision && it.getString(1) == "cold" && it.getString(2) == LocalMemoryCodec.sha(LocalMemoryCodec.bytes(text))
        }
        if (!current) return@transaction
        val exists = db.rawQuery("SELECT 1 FROM memory_cold_fts WHERE owner=? AND message_id=? LIMIT 1",arrayOf(owner,messageId)).use { it.moveToFirst() }
        if (!exists) indexMessage(db,owner,messageId,text,"memory_cold_fts")
    }
    private fun sequence(owner: String, id: String, messageId: String): Long = readableDatabase.rawQuery("SELECT seq FROM messages WHERE owner=? AND conversation_id=? AND id=?", arrayOf(owner, id, messageId)).use {
        if (!it.moveToFirst()) throw ApiFailure(404, "消息游标不属于这段聊天", "local_message_missing")
        it.getLong(0)
    }
    private fun indexMessage(db: SQLiteDatabase, owner: String, messageId: String, content: String,target:String="memory_fts") =
        LocalMemoryRules.parts(content).forEachIndexed { index, part ->
            val start = if (part.offset == 0) 0 else content.offsetByCodePoints(part.offset, -1)
            indexPart(db, owner, messageId, "", index, content.substring(start, part.offset + part.text.length),
                start, part.offset + part.text.length, content.length,target)
        }
    private fun indexPart(db: SQLiteDatabase, owner: String, messageId: String, documentId: String, index: Int, text: String,
                          start: Int = 0, end: Int = text.length, originalChars: Int = text.length,target:String="memory_fts") {
        require(target in setOf("memory_fts","memory_cold_fts"))
        db.insertOrThrow(target, null, values("body_tokens" to LocalMemoryRules.terms(text).joinToString(" "), "owner" to owner,
            "message_id" to messageId, "document_id" to documentId, "part_index" to index,
            "start_offset" to start, "end_offset" to end, "original_chars" to originalChars))
    }
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullText(name: String) = if (isNull(getColumnIndexOrThrow(name))) null else text(name)
    private fun Cursor.number(name: String) = getInt(getColumnIndexOrThrow(name))
    private data class MessageMetadata(val message: Message, val rawBytes: Int, val sha: String)
    private fun Cursor.metadata() = MessageMetadata(Message(text("id"), text("role"), "", JSONArray(text("sources")).mapObjects { it.source() },
        text("created_at"), JSONArray(text("attachments")).mapObjects { it.mediaAttachment() }, text("origin")), number("raw_bytes"), text("sha256"))
    private fun original(owner: String, id: String, row: MessageMetadata) = row.message.copy(
        content = LocalMemoryCodec.decode(payload(owner, id, row.message.id), row.rawBytes, row.sha))
    /** A single large blob must not exceed Android's CursorWindow just to be verified. */
    private fun payload(owner: String, id: String, messageId: String): ByteArray {
        val args = arrayOf(owner, id, messageId)
        val size = readableDatabase.rawQuery("SELECT length(payload) FROM messages WHERE owner=? AND conversation_id=? AND id=?", args).use {
            check(it.moveToFirst()) { "原文来源丢失" }; it.getInt(0)
        }
        require(size in 1..(LocalMemoryCodec.MAX_RAW_BYTES + 65536)) { "压缩原文大小校验失败" }
        val output = java.io.ByteArrayOutputStream(size)
        while (output.size() < size) {
            val chunk = readableDatabase.rawQuery("SELECT substr(payload,?,?) FROM messages WHERE owner=? AND conversation_id=? AND id=?",
                arrayOf((output.size() + 1).toString(), minOf(524288, size - output.size()).toString(), owner, id, messageId)).use {
                check(it.moveToFirst()) { "原文来源丢失" }; it.getBlob(0)
            }
            check(chunk.isNotEmpty() && output.size() + chunk.size <= size) { "压缩原文读取不完整" }
            output.write(chunk)
        }
        return output.toByteArray()
    }
    private fun Cursor.conversation() = Conversation(text("id"), "与你的日常", text("model"), number("use_rag") == 1,
        text("created_at"), text("updated_at"), nullText("profile")?.let { JSONObject(it).profile() })
    private fun Cursor.message(): Message = Message(text("id"), text("role"), LocalMemoryCodec.decode(getBlob(getColumnIndexOrThrow("payload")), number("raw_bytes"), text("sha256")),
        JSONArray(text("sources")).mapObjects { it.source() }, text("created_at"), JSONArray(text("attachments")).mapObjects { it.mediaAttachment() }, text("origin"))
    private fun sourceJson(sources: List<Source>) = JSONArray().also { array -> sources.forEach { source ->
        array.put(JSONObject().put("document_id", source.documentId).put("name", source.name).put("text", source.text).put("score", source.score))
    } }.toString()
    private fun mediaJson(media: List<MediaAttachment>) = JSONArray().also { array -> media.forEach { attachment ->
        MediaRules.check(attachment)
        array.put(JSONObject().put("id", attachment.id).put("kind", attachment.kind).put("url", attachment.url).put("mime_type", attachment.mimeType)
            .put("sha256", attachment.sha256).put("day_id", attachment.dayId ?: JSONObject.NULL).put("visual_version", attachment.visualVersion).put("source_label", attachment.sourceLabel))
    } }.toString()
    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply { pairs.forEach { (key, value) -> when (value) {
        null -> putNull(key); is String -> put(key, value); is Int -> put(key, value); is Long -> put(key, value); is ByteArray -> put(key, value)
        else -> throw IllegalArgumentException("本机数据类型无效")
    } } }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun identifier() = UUID.randomUUID().toString().replace("-", "")
}
