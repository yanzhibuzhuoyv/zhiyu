package com.zhiyu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.ByteArrayOutputStream

/** Isolated files/SQLite plus colored synthetic backgrounds, not model/vision/Key acceptance. */
class LocalScenePrefetchNativeTest {
    private val instant = Instant.parse("2026-10-12T06:00:00Z")
    private class Source : LocalSceneBackgroundSource {
        val requests = mutableListOf<Pair<String, String>>()
        var pending = false
        var terminalRest = false
        var accepted = true
        var reviews = 0
        val references = mutableListOf<ByteArray?>()
        var beforeReview: suspend () -> Unit = {}
        override fun hasInstalledPack() = true
        override suspend fun get(chat: String, day: String, version: Int, slot: LocalDailySlot): Bitmap? {
            requests.add(day to slot.time)
            if (terminalRest && slot.time == "00:00") throw LocalSceneBackgroundUnavailable("terminal")
            if (pending) return null
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                image.eraseColor(0xff000000.toInt() or ("$day:${slot.time}".hashCode() and 0x00ffffff))
                return checkNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
            } finally { image.recycle() }
        }
        override suspend fun reviewComposite(chat: String, day: String, version: Int, slot: LocalDailySlot,
            png: ByteArray, referenceOriginal: ByteArray?): Boolean {
            reviews++
            references.add(referenceOriginal)
            val image = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            assertTrue(image.width > 0 && image.height > 0); image.recycle()
            beforeReview()
            return accepted
        }
    }
    private suspend fun isolated(test: suspend (NativeV4Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = NativeV4Context(base)
        try { test(context) } finally {
            context.close()
            val root = context.filesDir.canonicalFile
            check(root.parentFile == base.filesDir.canonicalFile && root.name.startsWith("v4_"))
            check(root.deleteRecursively())
        }
    }
    private fun stock(context: NativeV4Context, source: Source, resource: LocalScenePrefetchResources = LocalScenePrefetchResources(.0, true, true)) =
        LocalSceneStock(context, source, { instant }) { resource }

    @Test fun approvedSyntheticFutureStockIsPrioritizedAndCannotBeSentAsTodaysPicture() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source(); val worker = stock(context, source)
            val result = worker.prepare(chat)
            assertEquals(1, result.prepared); assertEquals("yield", result.reason)
            assertEquals("2026-10-13", source.requests.first().first)
            val media = LocalMediaStore(context)
            val asset = media.list("image").single(); val details = media.details(asset.id)
            assertEquals("2026-10-13", asset.dayId)
            assertTrue("qwen_generated" in details.tags && "composite_reviewed" in details.tags)
            assertFalse(LocalDayStockRules.available(asset, details.tags.toSet(), "2026-10-12", "23:59", 0))
            assertTrue(LocalDayStockRules.available(asset, details.tags.toSet(), "2026-10-13", "00:00", 0))
        }
    }

    @Test fun pendingInstalledModelDoesNotFallBackToCanvasOrPublishAFalseSuccess() = runBlocking {
        isolated { context ->
            val source = Source().apply { pending = true }
            val result = stock(context, source).prepare(LocalMediaStore.identifier())
            assertEquals("pending", result.reason); assertEquals(0, result.prepared)
            assertEquals(1, source.requests.size); assertEquals(0, source.reviews)
            assertTrue(LocalMediaStore(context).list("image").isEmpty())
        }
    }

    @Test fun terminalUnknownSlotDoesNotBlockTheRestOfTomorrowsStockOrCountAsReady() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source().apply { terminalRest = true }
            val result = stock(context, source).prepare(chat)
            assertEquals(1, result.prepared)
            val asset = LocalMediaStore(context).list("image").single()
            assertEquals("2026-10-13", asset.dayId)
            assertFalse("slot_0000" in LocalMediaStore(context).details(asset.id).tags)
            assertEquals("00:00", source.requests.first().second)
            assertEquals(1, source.reviews)
        }
    }

    @Test fun landscapeReviewOmitsOcReferenceButPortraitAndPovKeepTheOriginalPixels() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source()
            val reference = Bitmap.createBitmap(24, 40, Bitmap.Config.ARGB_8888)
            val raw = try {
                reference.eraseColor(0xff917799.toInt())
                ByteArrayOutputStream().use { output -> check(reference.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            } finally { reference.recycle() }
            LocalVisualRepository(context).import(chat, raw)
            val worker = stock(context, source)
            repeat(3) { worker.prepare(chat) }
            assertEquals(3, source.references.size)
            assertNull(source.references[0]) // The rest scene is an empty landscape.
            assertArrayEquals(raw, source.references[1]) // Morning portrait.
            assertArrayEquals(raw, source.references[2]) // Afternoon POV: identity applies only to visible details.
        }
    }

    @Test fun rejectedCompositeIsNotPublishedAndReviewWorkIsBoundedToOnePerRun() = runBlocking {
        isolated { context ->
            val source = Source().apply { accepted = false }
            val result = stock(context, source).prepare(LocalMediaStore.identifier())
            assertEquals(0, result.prepared); assertEquals("yield", result.reason)
            assertEquals(1, source.reviews)
            assertTrue(LocalMediaStore(context).list("image").isEmpty())
        }
    }

    @Test fun nextDayUrgencyNeverBypassesUnknownThermalOrLowMemory() = runBlocking {
        for (resource in listOf(LocalScenePrefetchResources(null, null, true), LocalScenePrefetchResources(.0, true, false))) {
            isolated { context ->
                val source = Source()
                val result = stock(context, source, resource).prepare(LocalMediaStore.identifier())
                assertTrue(result.deferred); assertEquals(0, result.prepared)
                assertTrue(source.requests.isEmpty()); assertEquals(0, source.reviews)
            }
        }
    }

    @Test fun allTomorrowSlotsAreReadyBeforeUnknownCpuDefersOptionalLaterStock() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source()
            val worker = stock(context, source, LocalScenePrefetchResources(null, true, true))
            val tomorrow = LocalDailyRepository(context).use { it.horizon(chat, 0, instant)[1] }
            var result = worker.prepare(chat)
            repeat(tomorrow.slots.size) { if (result.reason == "yield") result = worker.prepare(chat) }
            assertEquals("cpu_unknown", result.reason)
            val assets = LocalMediaStore(context).list("image")
            assertEquals(tomorrow.slots.size, assets.size)
            assertTrue(assets.all { it.dayId == tomorrow.dayId })
        }
    }

    @Test fun appearanceChangingDuringReviewCannotPublishOldIdentityPixels() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source()
            source.beforeReview = { LocalVisualRepository(context).update(chat, "none", "", "male", "long") }
            try { stock(context, source).prepare(chat); fail("Expected identity CAS rejection") }
            catch (_: IllegalArgumentException) { }
            assertTrue(LocalMediaStore(context).list("image").isEmpty())
            assertEquals(1, LocalVisualRepository(context).profile(chat).visualVersion)
        }
    }

    @Test fun persistedFutureTrajectoryChangingDuringReviewCannotPublishTheOldPrompt() = runBlocking {
        isolated { context ->
            val chat = LocalMediaStore.identifier(); val source = Source()
            source.beforeReview = {
                LocalDailyRepository(context).use { days ->
                    val row = days.readableDatabase.rawQuery("SELECT payload FROM days WHERE chat=? AND day=?", arrayOf(chat, "2026-10-13")).use {
                        check(it.moveToFirst()); it.getString(0)
                    }
                    val json = JSONObject(row)
                    json.getJSONArray("slots").getJSONObject(0).put("activity", "新的虚构休息安排")
                    val payload = json.toString()
                    days.writableDatabase.execSQL("UPDATE days SET payload=?,sha=? WHERE chat=? AND day=?",
                        arrayOf(payload, LocalMemoryCodec.sha(payload.toByteArray(Charsets.UTF_8)), chat, "2026-10-13"))
                }
            }
            try { stock(context, source).prepare(chat); fail("Expected actual trajectory CAS rejection") }
            catch (_: IllegalArgumentException) { }
            assertTrue(LocalMediaStore(context).list("image").isEmpty())
        }
    }
}
