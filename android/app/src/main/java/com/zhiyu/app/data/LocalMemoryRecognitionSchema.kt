package com.zhiyu.app.data

import android.database.sqlite.SQLiteDatabase

/** Called from the chat database's schema transaction; no original or FTS table is recreated. */
object LocalMemoryRecognitionSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS native_memory_jobs (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation_id TEXT NOT NULL,
            message_id TEXT NOT NULL REFERENCES messages(id), provider_id TEXT NOT NULL, model TEXT NOT NULL,
            source_sha TEXT NOT NULL, source_revision INTEGER NOT NULL, source_role TEXT NOT NULL,
            source_created_at TEXT NOT NULL, source_offset INTEGER NOT NULL DEFAULT 0,
            version TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            lease TEXT, updated_ms INTEGER NOT NULL,
            UNIQUE(owner,conversation_id,message_id,version))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS native_memory_jobs_scope ON native_memory_jobs(owner,conversation_id,status,updated_ms)")
        val columns = db.rawQuery("PRAGMA table_info(native_memory_jobs)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        if ("source_role" !in columns) db.execSQL("ALTER TABLE native_memory_jobs ADD COLUMN source_role TEXT NOT NULL DEFAULT ''")
        if ("source_created_at" !in columns) db.execSQL("ALTER TABLE native_memory_jobs ADD COLUMN source_created_at TEXT NOT NULL DEFAULT ''")
        db.execSQL("""CREATE TABLE IF NOT EXISTS native_memory_annotations (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, conversation_id TEXT NOT NULL,
            message_id TEXT NOT NULL REFERENCES messages(id), role TEXT NOT NULL, source_created_at TEXT NOT NULL,
            source_sha TEXT NOT NULL, source_revision INTEGER NOT NULL,
            span_start INTEGER NOT NULL, span_end INTEGER NOT NULL, span_sha TEXT NOT NULL,
            kind TEXT NOT NULL, subject TEXT NOT NULL, polarity TEXT NOT NULL, topic_key TEXT NOT NULL,
            confidence REAL NOT NULL, explicit_keep INTEGER NOT NULL, correction INTEGER NOT NULL,
            promise_state TEXT, date_basis TEXT, event_date TEXT, validity TEXT NOT NULL DEFAULT 'current',
            tier TEXT NOT NULL DEFAULT 'mid', pinned INTEGER NOT NULL DEFAULT 0,
            evidence_count INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT 'unclassified',
            version TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
            CHECK(span_start>=0 AND span_end>span_start),
            UNIQUE(owner,message_id,version,span_start,span_end,kind,topic_key))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS native_memory_annotation_topic ON native_memory_annotations(owner,conversation_id,kind,subject,topic_key,validity)")
        db.execSQL("CREATE INDEX IF NOT EXISTS native_memory_annotation_message ON native_memory_annotations(owner,conversation_id,message_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS native_memory_annotation_links (
            newer_id TEXT NOT NULL REFERENCES native_memory_annotations(id),
            older_id TEXT NOT NULL REFERENCES native_memory_annotations(id), relation TEXT NOT NULL CHECK(relation='supersedes'),
            PRIMARY KEY(newer_id,older_id), CHECK(newer_id!=older_id))""")
    }
}
