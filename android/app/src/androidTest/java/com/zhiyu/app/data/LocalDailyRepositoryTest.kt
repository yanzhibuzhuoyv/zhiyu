package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import java.security.MessageDigest

class LocalDailyRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun firstPlanImmediatelyPersistsTodayAndTheFollowingTwoDays() {
        val name = "daily_first_horizon_${UUID.randomUUID()}.sqlite3"
        val now = Instant.parse("2026-10-12T06:00:00Z")
        try {
            LocalDailyRepository(context, name).use { store ->
                store.scene("a".repeat(32), 2, now)
                val days = store.readableDatabase.rawQuery("SELECT day,created_at FROM days ORDER BY day", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
                }
                assertEquals(listOf("2026-10-12", "2026-10-13", "2026-10-14"), days.map { it.first })
                assertTrue(days.all { it.second == now.toString() })
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun prefetchedFutureActivityBecomesCurrentOnlyOnThatDayAndKeepsItsOriginalCreationTime() {
        val name = "daily_future_${UUID.randomUUID()}.sqlite3"
        val start = Instant.parse("2026-10-12T10:00:00Z")
        try {
            val future = LocalDailyRepository(context, name).use { store ->
                val plans = store.horizon("a".repeat(32), 2, start)
                assertEquals("00:00", plans[1].current.time)
                plans[1]
            }
            LocalDailyRepository(context, name).use { store ->
                val next = store.scene("a".repeat(32), 2, start.plusSeconds(86400))
                assertEquals(future.slots, next.slots); assertEquals(future.sceneId, next.sceneId)
                assertEquals(com.zhiyu.app.domain.LocalDailyRules.current(next.slots, start.plusSeconds(86400)), next.current)
                assertEquals(start.toString(), store.readableDatabase.rawQuery("SELECT created_at FROM days WHERE day=?", arrayOf("2026-10-13")).use {
                    check(it.moveToFirst()); it.getString(0)
                })
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun dayPlanPersistsAcrossRestartAndMidnightWithoutLosingTheOldDay() {
        val name = "daily_test_${UUID.randomUUID()}.sqlite3"
        val id = "a".repeat(32)
        val morning = Instant.parse("2026-10-08T00:00:00Z")
        try {
            val first = LocalDailyRepository(context, name).use { it.scene(id, 2, morning) }
            LocalDailyRepository(context, name).use { store ->
                assertEquals(first, store.scene(id, 2, morning))
                val night = store.scene(id, 2, Instant.parse("2026-10-08T16:00:00Z"))
                assertNotEquals(first.dayId, night.dayId)
                assertEquals("00:00", night.current.time)
                assertEquals(first, store.scene(id, 2, morning))
                val count = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM days", null).use { it.moveToFirst(); it.getInt(0) }
                assertEquals(4, count)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun appearanceChangesUseTheSameDayAndSeparateConversationsHaveSeparateRows() {
        val name = "daily_test_${UUID.randomUUID()}.sqlite3"
        val now = Instant.parse("2026-10-09T04:00:00Z")
        try {
            LocalDailyRepository(context, name).use { store ->
                val first = store.scene("a".repeat(32), 2, now)
                val update = store.scene("a".repeat(32), 3, now)
                assertEquals(first.slots, update.slots)
                assertEquals(first.sceneId, update.sceneId)
                assertEquals(3, update.visualVersion)
                val other = store.scene("b".repeat(32), 3, now)
                assertNotEquals(first.sceneId, other.sceneId)
                assertTrue(store.prompt(update).contains("不是用户的位置"))
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun corruptedDayPlanCannotBecomeAQuotedFact() {
        val name = "daily_test_${UUID.randomUUID()}.sqlite3"
        val now = Instant.parse("2026-10-09T04:00:00Z")
        try {
            LocalDailyRepository(context, name).use { store ->
                store.scene("a".repeat(32), 0, now)
                store.writableDatabase.execSQL("UPDATE days SET payload='unverified user location'")
                assertThrows(IllegalStateException::class.java) { store.scene("a".repeat(32), 0, now) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun aPersistedCustomActivityIsNotReplacedByTemplatesOnRestartOrClockRollback() {
        val name = "daily_test_${UUID.randomUUID()}.sqlite3"
        val id = "a".repeat(32)
        val before = Instant.parse("2026-10-09T04:00:00Z")
        try {
            LocalDailyRepository(context, name).use { store ->
                store.scene(id, 0, before)
                val raw = store.readableDatabase.rawQuery("SELECT payload FROM days WHERE chat=?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }
                val json = JSONObject(raw)
                json.getJSONArray("slots").getJSONObject(0).put("activity", "写一段自己的日记")
                val payload = json.toString()
                val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
                store.writableDatabase.execSQL("UPDATE days SET payload=?,sha=? WHERE chat=? AND day=?", arrayOf(payload, sha, id, "2026-10-09"))
            }
            LocalDailyRepository(context, name).use { store ->
                assertEquals("写一段自己的日记", store.scene(id, 0, before).slots.first().activity)
                store.scene(id, 0, before.plusSeconds(86400))
                assertEquals("写一段自己的日记", store.scene(id, 0, before).slots.first().activity)
                assertEquals(4, store.readableDatabase.rawQuery("SELECT COUNT(*) FROM days", null).use { it.moveToFirst(); it.getInt(0) })
            }
        } finally { context.deleteDatabase(name) }
    }
}
