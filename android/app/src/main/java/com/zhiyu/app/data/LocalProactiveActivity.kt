package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.LocalMemoryCodec
import com.zhiyu.app.domain.ProactiveRules
import java.time.Instant

internal data class LocalProactiveActivity(val messageId: String, val createdAt: Instant)

/** Current binding only, one metadata row; no decompression, Key or full-history scan. */
internal fun latestProactiveActivity(context: Context, conversationId: String): LocalProactiveActivity? {
    ProactiveRules.conversationId(conversationId)
    val binding = appCredentialStore(context).readBinding() ?: return null
    if (!context.getDatabasePath("zhiyu_local_v1.sqlite3").isFile) return null
    val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray(Charsets.UTF_8))
    return LocalChatDatabase(context).use { database ->
        val conversation = database.findConversation(owner) ?: return@use null
        if (conversation.id != conversationId || conversation.model != binding.model) return@use null
        database.readableDatabase.rawQuery("SELECT id,created_at FROM messages WHERE owner=? AND conversation_id=? " +
            "AND role='user' ORDER BY seq DESC LIMIT 1", arrayOf(owner, conversationId)).use { cursor ->
            if (cursor.moveToFirst()) LocalProactiveActivity(cursor.getString(0), Instant.parse(cursor.getString(1))) else null
        }
    }
}
