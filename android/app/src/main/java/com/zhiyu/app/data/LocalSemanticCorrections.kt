package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import java.time.Instant

data class LocalSemanticCorrectionResult(val originals:List<LocalRecall>,val unresolved:Boolean)

/** Follow bounded original-pointer links. Corrections replace context candidates, never the archive. */
class LocalSemanticCorrections(private val archive:LocalChatDatabase) {
    private data class Node(val id:String,val messageId:String,val role:String,val createdAt:String,val sha:String,
        val start:Int,val end:Int,val spanSha:String,val tier:String,val validity:String)
    private data class Corrected(val original:LocalRecall,val requiresSupplement:Boolean)
    fun complete(owner:String,id:String,originals:List<LocalRecall>):LocalSemanticCorrectionResult {
        require(originals.size <= 8)
        var unresolved = false
        val corrections = linkedMapOf<String,LocalRecall>()
        val available = originals.associateBy { it.messageId }
        val ordinary = mutableListOf<LocalRecall>()
        for (source in originals) {
            val anchors = anchors(owner,id,source)
            if (anchors.isEmpty()) { ordinary.add(source); continue }
            val found = successors(owner,id,anchors)
            val supplied = found.first.mapNotNull { pointer ->
                if (corrections.size >= 8 && pointer.messageId !in corrections) { unresolved = true; return@mapNotNull null }
                try {
                    val corrected = original(owner,id,pointer,source,available[pointer.messageId])
                    if (corrected.requiresSupplement) unresolved = true
                    corrected.original
                }
                catch (_:IllegalArgumentException) { unresolved = true; null }
                catch (_:IllegalStateException) { unresolved = true; null }
            }
            val missing = found.second || supplied.isEmpty() || supplied.size != found.first.size
            if (missing) {
                unresolved = true
                ordinary.add(source.copy(evidenceState="superseded_unresolved"))
            }
            supplied.forEach { correction ->
                val existing = corrections[correction.messageId]
                corrections[correction.messageId] = if (existing == null) correction else
                    existing.copy(correctionOf=(existing.correctionOf + source.messageId).distinct())
            }
        }
        val selected = corrections.values.sortedByDescending { Instant.parse(it.createdAt) } + ordinary
        var used = 0
        val bounded = selected.distinctBy { it.messageId }.take(8).mapNotNull { source ->
            if (used + source.text.length > 12000) { unresolved = true; null }
            else source.also { used += it.text.length }
        }
        return LocalSemanticCorrectionResult(bounded,unresolved)
    }
    private fun anchors(owner:String,id:String,source:LocalRecall):List<String> = archive.readableDatabase.rawQuery("""SELECT a.id
        FROM native_memory_annotations a JOIN messages m ON m.id=a.message_id AND m.owner=a.owner
        AND m.conversation_id=a.conversation_id AND m.sha256=a.source_sha AND m.role=a.role AND m.created_at=a.source_created_at
        WHERE a.owner=? AND a.conversation_id=? AND a.message_id=? AND a.validity='superseded'
        AND a.span_start<? AND a.span_end>? ORDER BY a.span_start LIMIT 9""",
        arrayOf(owner,id,source.messageId,source.endOffset.toString(),source.startOffset.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun successors(owner:String,id:String,anchors:List<String>):Pair<List<Node>,Boolean> {
        val queue = java.util.ArrayDeque<Pair<String,Int>>()
        anchors.take(8).forEach { queue.add(it to 0) }
        val visited = mutableSetOf<String>(); val current = linkedMapOf<String,Node>()
        var incomplete = anchors.size > 8
        while (queue.isNotEmpty() && visited.size < 64) {
            val (older,depth) = queue.removeFirst()
            if (!visited.add(older)) continue
            if (depth >= 8) { incomplete = true; continue }
            val newer = nodes(owner,id,older)
            if (newer.isEmpty() || newer.size > 8) incomplete = true
            newer.take(8).forEach { node -> when (node.validity) {
                "current" -> current[node.id] = node
                "superseded" -> queue.add(node.id to depth + 1)
                else -> incomplete = true
            } }
        }
        if (queue.isNotEmpty() || current.size > 8) incomplete = true
        return current.values.take(8) to incomplete
    }
    private fun nodes(owner:String,id:String,older:String):List<Node> = archive.readableDatabase.rawQuery("""SELECT
        n.id,n.message_id,n.role,n.source_created_at,n.source_sha,n.span_start,n.span_end,n.span_sha,m.tier,n.validity
        FROM native_memory_annotation_links l JOIN native_memory_annotations o ON o.id=l.older_id
        JOIN native_memory_annotations n ON n.id=l.newer_id
        JOIN messages om ON om.id=o.message_id AND om.owner=o.owner AND om.conversation_id=o.conversation_id
          AND om.sha256=o.source_sha AND om.role=o.role AND om.created_at=o.source_created_at
        JOIN messages m ON m.id=n.message_id AND m.owner=n.owner AND m.conversation_id=n.conversation_id
          AND m.sha256=n.source_sha AND m.role=n.role AND m.created_at=n.source_created_at
        WHERE l.older_id=? AND l.relation='supersedes' AND o.owner=? AND o.conversation_id=? AND o.validity='superseded'
          AND n.owner=o.owner AND n.conversation_id=o.conversation_id AND n.kind=o.kind
          AND n.subject=o.subject AND n.topic_key=o.topic_key AND n.role=o.role AND m.seq>om.seq
        ORDER BY m.seq DESC,n.span_start LIMIT 9""",arrayOf(older,owner,id)).use { cursor -> buildList {
        while (cursor.moveToNext()) add(Node(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),
            cursor.getString(4),cursor.getInt(5),cursor.getInt(6),cursor.getString(7),cursor.getString(8),cursor.getString(9)))
    } }
    private fun original(owner:String,id:String,node:Node,older:LocalRecall,available:LocalRecall?):Corrected {
        val source = archive.recognitionOriginal(owner,id,node.messageId) ?: throw IllegalStateException("更正原文来源丢失")
        require(source.sha256 == node.sha && source.role == node.role && source.createdAt == node.createdAt)
        require(node.start >= 0 && node.end in (node.start + 1)..source.text.length && node.end - node.start <= LocalMemoryRecognitionRules.MAX_WINDOW)
        val span = source.text.substring(node.start,node.end)
        require(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(span)) == node.spanSha) { "更正原文片段校验失败" }
        val supplied = available?.takeIf { it.role == source.role && it.createdAt == source.createdAt }?.let {
            LocalCorrectionSpan(it.startOffset,it.endOffset,it.text,true)
        }
        val selected = LocalCorrectionSpanRules.select(source.text,node.start,node.end,supplied)
        // This score expresses the searched ancestor's relevance; graph closure does not measure a new cosine.
        val original = LocalRecall(source.messageId,source.role,selected.text,source.createdAt,node.tier,older.score,
            selected.start,selected.end,source.text.length,"current_correction",listOf(older.messageId),origin = source.origin)
        return Corrected(original,selected.requiresSupplement)
    }
}
