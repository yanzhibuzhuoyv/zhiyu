package com.zhiyu.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Synthetic private databases only; retries reserve receipts and never invoke an image service. */
class LocalImageJobStoreNativeTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val chat = "b".repeat(32)
    private val otherChat = "c".repeat(32)
    private fun id(number: Int) = number.toString(16).padStart(32, '0')
    private fun job(number: Int, state: String = "reserved", kind: String = "scene", owner: String = chat) =
        LocalGenerationJob(id(number), owner, kind, 3, "2026-10-14", "d".repeat(64), state)
    private fun isolated(test: (NativeV4Context) -> Unit) = NativeV4Context(base).use { context ->
        try { test(context) } finally { context.filesDir.deleteRecursively() }
    }
    private fun finished(store: LocalImageJobStore, value: LocalGenerationJob) {
        store.reserve(value.copy(state = "reserved")); store.finish(value.requestId, value.state)
    }

    @Test fun v1MigrationKeepsAllOriginalJobColumnsAndAddsAnEmptyRetryLedger() = isolated { context ->
        val file = context.getDatabasePath("migration.sqlite3")
        val values = listOf(job(1, "failed"), job(2, "success", "oc"), job(3, "running"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE jobs(request_id TEXT PRIMARY KEY,chat_id TEXT NOT NULL,kind TEXT NOT NULL," +
                "version INTEGER NOT NULL,day TEXT NOT NULL,prompt_sha TEXT NOT NULL,state TEXT NOT NULL,asset_id TEXT,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX jobs_owner ON jobs(chat_id,kind,state)")
            values.forEachIndexed { index, value -> db.insertOrThrow("jobs", null, ContentValues().apply {
                put("request_id", value.requestId); put("chat_id", value.chatId); put("kind", value.kind)
                put("version", value.expectedVersion); put("day", value.dayId); put("prompt_sha", value.promptHash)
                put("state", value.state); put("asset_id", id(100 + index)); put("created_at", 1700000000000L + index)
            }) }
            db.version = 1
        }
        LocalImageJobStore(context, "migration.sqlite3").use { store ->
            assertEquals(2, store.writableDatabase.version)
            assertEquals(StoredImageJob(values[0], id(100)), store.get(id(1)))
            assertEquals(StoredImageJob(values[1], id(101)), store.get(id(2)))
            assertEquals(StoredImageJob(values[2].copy(state = "unknown"), id(102)), store.get(id(3)))
            assertNull(store.retryFor(id(1))); assertNull(store.retrySource(id(1)))
            store.writableDatabase.rawQuery("SELECT created_at FROM jobs ORDER BY request_id", null).use { rows ->
                values.indices.forEach { index -> assertTrue(rows.moveToNext()); assertEquals(1700000000000L + index, rows.getLong(0)) }
                assertFalse(rows.moveToNext())
            }
        }
    }

    @Test fun scenePagesAreBoundedNewestFirstAndStableAcrossEqualTimestampsAndNewRows() = isolated { context ->
        LocalImageJobStore(context, "pages.sqlite3").use { store ->
            (1..65).forEach { finished(store, job(it, "failed")) }
            finished(store, job(70, "failed", owner = otherChat)); finished(store, job(71, "failed", kind = "oc"))
            store.writableDatabase.execSQL("UPDATE jobs SET created_at=1700000000000")
            val defaultPage = store.scenePage(chat)
            assertEquals(30, defaultPage.items.size); assertNotNull(defaultPage.nextCursor)
            val first = store.scenePage(chat, 11)
            assertEquals((55..65).reversed().map(::id), first.items.map { it.job.requestId })
            finished(store, job(90, "failed")) // A newer insertion must not repeat or shift old cursor rows.
            val all = first.items.toMutableList(); var cursor = first.nextCursor
            while (cursor != null) {
                val page = store.scenePage(chat, 11, cursor)
                assertTrue(page.items.size <= 11); all.addAll(page.items); cursor = page.nextCursor
            }
            assertEquals((1..65).reversed().map(::id), all.map { it.job.requestId })
            assertTrue(all.all { it.job.chatId == chat && it.job.kind == "scene" })
        }
    }

    @Test fun pageRejectsWrongChatInvalidLimitsAndACursorWhoseAnchorIsNoLongerAScene() = isolated { context ->
        LocalImageJobStore(context, "cursor.sqlite3").use { store ->
            (1..3).forEach { finished(store, job(it, "failed")) }
            val page = store.scenePage(chat, 1); val cursor = requireNotNull(page.nextCursor)
            assertThrows(IllegalArgumentException::class.java) { store.scenePage(otherChat, 1, cursor) }
            listOf(0, 101).forEach { assertThrows(IllegalArgumentException::class.java) { store.scenePage(chat, it) } }
            listOf("broken", "x".repeat(1025)).forEach { value -> assertThrows(IllegalArgumentException::class.java) { store.scenePage(chat, 1, value) } }
            store.writableDatabase.execSQL("UPDATE jobs SET kind='oc' WHERE request_id=?", arrayOf(page.items.last().job.requestId))
            assertThrows(IllegalArgumentException::class.java) { store.scenePage(chat, 1, cursor) }
        }
    }

    @Test fun oneExplicitFailedSceneRetryPersistsBidirectionalReceiptWithoutChangingSource() = isolated { context ->
        val source = job(1, "failed"); val retry = job(2)
        LocalImageJobStore(context, "retry.sqlite3").use { store ->
            finished(store, source); val before = store.get(source.requestId)
            store.reserveRetry(source.requestId, retry)
            assertEquals(before, store.get(source.requestId))
            assertEquals(StoredImageJob(retry, null), store.get(retry.requestId))
            assertEquals(retry.requestId, store.retryFor(source.requestId)); assertEquals(source.requestId, store.retrySource(retry.requestId))
        }
        LocalImageJobStore(context, "retry.sqlite3").use { store ->
            assertEquals(retry.requestId, store.retryFor(source.requestId)); assertEquals(source.requestId, store.retrySource(retry.requestId))
            assertEquals(source, store.get(source.requestId)!!.job)
        }
    }

    @Test fun repeatedClicksAndRetryOfRetryCannotCreateAnotherPaidOpportunity() = isolated { context ->
        LocalImageJobStore(context, "once.sqlite3").use { store ->
            val source = job(1, "failed"); val retry = job(2)
            finished(store, source); store.reserveRetry(source.requestId, retry)
            listOf(retry, job(3)).forEach { candidate ->
                assertThrows(ApiFailure::class.java) { store.reserveRetry(source.requestId, candidate) }
            }
            store.finish(retry.requestId, "failed")
            assertThrows(ApiFailure::class.java) { store.reserveRetry(retry.requestId, job(4)) }
            assertNull(store.get(id(3))); assertNull(store.get(id(4))); assertNull(store.retryFor(retry.requestId))
            assertEquals(retry.requestId, store.retryFor(source.requestId))
        }
    }

    @Test fun unknownPendingSucceededStaleAndOcJobsCannotBecomeExplicitRetries() = isolated { context ->
        LocalImageJobStore(context, "outcomes.sqlite3").use { store ->
            val sources = listOf(job(1, "unknown"), job(2, "running"), job(3, "reserved"), job(4, "success"), job(5, "stale"), job(6, "failed", "oc"))
            sources.forEachIndexed { index, source ->
                finished(store, source)
                assertThrows(ApiFailure::class.java) { store.reserveRetry(source.requestId, job(100 + index)) }
                assertNull(store.retryFor(source.requestId)); assertNull(store.get(id(100 + index)))
            }
        }
    }

    @Test fun mismatchedRetryBindingOrAnExistingTargetRollsBackBothReceiptAndNewJob() = isolated { context ->
        LocalImageJobStore(context, "rollback.sqlite3").use { store ->
            val source = job(1, "failed"); finished(store, source)
            listOf(job(2, owner = otherChat), job(2).copy(expectedVersion = 4), job(2).copy(dayId = "2026-10-15"),
                job(2, kind = "oc"), job(2, state = "running")).forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { store.reserveRetry(source.requestId, invalid) }
                assertNull(store.retryFor(source.requestId)); assertNull(store.get(id(2)))
            }
            val existing = job(3); store.reserve(existing)
            assertThrows(ApiFailure::class.java) { store.reserveRetry(source.requestId, existing) }
            assertNull(store.retryFor(source.requestId)); assertNull(store.retrySource(existing.requestId))
            assertEquals(source, store.get(source.requestId)!!.job); assertEquals(existing, store.get(existing.requestId)!!.job)
        }
    }

    @Test fun concurrentStoresReserveExactlyOneRetryAndKeepTheOtherRowsAbsent() = isolated { context ->
        LocalImageJobStore(context, "race.sqlite3").use { first -> LocalImageJobStore(context, "race.sqlite3").use { second ->
            val source = job(1, "failed"); finished(first, source)
            val executor = Executors.newFixedThreadPool(2); val ready = CountDownLatch(2); val start = CountDownLatch(1)
            try {
                val attempts = listOf(first to job(2), second to job(3)).map { (store, candidate) -> executor.submit<Boolean> {
                    ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                    try { store.reserveRetry(source.requestId, candidate); true } catch (_: ApiFailure) { false }
                } }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown()
                assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
                val retry = requireNotNull(first.retryFor(source.requestId))
                assertEquals(source.requestId, second.retrySource(retry))
                assertEquals(2, first.scenePage(chat).items.size)
                assertEquals(1, listOf(id(2), id(3)).count { first.get(it) != null })
            } finally { start.countDown(); executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS)) }
        } }
    }

    @Test fun retrySuccessKeepsItsOwnOutcomeAndOriginalFailureAsIndependentImmutableHistory() = isolated { context ->
        LocalImageJobStore(context, "success.sqlite3").use { store ->
            val source = job(1, "failed"); finished(store, source); store.reserveRetry(source.requestId, job(2))
            store.finish(id(2), "success", id(30))
            assertEquals(StoredImageJob(job(2, "success"), id(30)), store.get(id(2)))
            assertEquals(source, store.get(id(1))!!.job); assertEquals(id(2), store.retryFor(id(1)))
            assertThrows(ApiFailure::class.java) { store.reserveRetry(id(1), job(3)) }
            assertEquals(2, store.scenePage(chat).items.size)
        }
    }
}
