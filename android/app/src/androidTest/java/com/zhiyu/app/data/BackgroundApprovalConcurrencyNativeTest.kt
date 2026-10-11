package com.zhiyu.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Generated synthetic pixels only. No cloud, production credentials, OC or user photos are read. */
class BackgroundApprovalConcurrencyNativeTest {
    private data class Cached(val chat: String, val day: String, val slot: LocalDailySlot, val image: AgnesGeneratedImage)
    private fun cached(context: Context, purpose: String = "scene_background"): Cached {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff98abbc.toInt()) }
        val bytes = ByteArrayOutputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); bitmap.recycle(); out.toByteArray()
        }
        val chat = LocalMediaStore.identifier(); val day = "2026-10-11"
        val slot = LocalDailyRules.scene(chat, 0, Instant.parse("2026-10-11T04:00:00Z")).slots.first()
        val prompt = "生成一张自然随手拍风格的空场景，画面有变化和生活感。不要任何人物、人体、脸、翅膀、武器、文字或水印。" +
            "时间${slot.time}，场景主题${slot.activity}，配色自然。画面稍有留白方便在本机合成角色。"
        val hash = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(prompt))
        val request = LocalMemoryCodec.sha("$chat:$day:0:$hash".toByteArray()).take(32)
        val asset = LocalMediaStore(context).ingest("image", bytes, "合成生成背景", visualVersion = 0, dayId = day, purpose = purpose)
        LocalImageJobStore(context).use { jobs ->
            jobs.reserve(LocalGenerationJob(request, chat, "scene", 0, day, hash, "reserved"))
            jobs.finish(request, "running"); jobs.finish(request, "success", asset.id)
        }
        AgnesCredentialStore(context).write("synthetic-image-key")
        return Cached(chat, day, slot, AgnesGeneratedImage(bytes, "image/png"))
    }

    private fun scenes(context: Context, approval: BackgroundImageApproval) = LocalGeneratedBackgrounds(context,
        AgnesImageClient(AgnesImageTransport { _, _ -> error("An old background must never regenerate") }), approval)

    @Test fun concurrentOldCacheChecksAcrossInstancesShareOnePaidAssessment(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context); val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val approval = BackgroundImageApproval {
                calls.incrementAndGet(); entered.complete(Unit); release.await(); true
            }
            val first = async(Dispatchers.Default) { scenes(context, approval).get(source.chat, source.day, 0, source.slot) }
            withTimeout(5_000) { entered.await() }
            val second = async(Dispatchers.Default) { scenes(context, approval).get(source.chat, source.day, 0, source.slot) }
            try {
                // The second caller reaches the busy assessment while the first cannot finish.
                delay(300)
                assertEquals("One generated SHA/schema must share one charge", 1, calls.get())
            } finally { release.complete(Unit) }
            val results = withTimeout(5_000) { listOf(first.await(), second.await()) }
            assertTrue(results.all { it != null }); results.filterNotNull().forEach { it.recycle() }
            assertEquals(1, calls.get())
            scenes(context, BackgroundImageApproval { error("Persisted approval must not be paid again") })
                .get(source.chat, source.day, 0, source.slot)?.recycle()
        }
    }

    @Test fun rejectionSurvivesReopenAndNeverRegeneratesOrApprovesThatSha(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context); var calls = 0
            val approval = BackgroundImageApproval { calls++; false }
            repeat(2) { assertNull(scenes(context, approval).get(source.chat, source.day, 0, source.slot)) }
            assertEquals("An explicit rejection must not be charged again after store reopen", 1, calls)
            assertFalse(BackgroundApprovalStore(context).approved(source.image))
        }
    }

    @Test fun serviceFailureSurvivesReopenWithoutRetryOrAccidentalApproval(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context); var calls = 0
            val approval = BackgroundImageApproval { calls++; throw ApiFailure(503, "synthetic service failure") }
            repeat(2) { assertNull(scenes(context, approval).get(source.chat, source.day, 0, source.slot)) }
            assertEquals("A lost assessment must not silently restart a charged call", 1, calls)
            assertFalse(BackgroundApprovalStore(context).approved(source.image))
        }
    }

    @Test fun callerCancellationPropagatesAndLeavesNoReusableApprovalOrAutomaticRetry(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context); var calls = 0
            val cancelled = BackgroundImageApproval { calls++; throw CancellationException("synthetic cancellation") }
            try { scenes(context, cancelled).get(source.chat, source.day, 0, source.slot); fail("Caller cancellation must escape") }
            catch (_: CancellationException) { }
            assertFalse(BackgroundApprovalStore(context).approved(source.image))
            assertNull(scenes(context, BackgroundImageApproval { calls++; true }).get(source.chat, source.day, 0, source.slot))
            assertEquals("Cancelled assessment has unknown charge state, not a retry opportunity", 1, calls)
        }
    }

    @Test fun interruptedCheckingReceiptNeverRestartsAnUnknownChargedAssessment(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context); val sha = LocalMemoryCodec.sha(source.image.bytes)
            LocalMediaStore.write(File(context.filesDir, "background_approvals/$sha.json"),
                "{\"schema\":\"${BackgroundContentRules.SCHEMA}\",\"sha256\":\"$sha\",\"state\":\"checking\"}".toByteArray())
            assertNull(scenes(context, BackgroundImageApproval { error("An interrupted claim must never be automatically paid again") })
                .get(source.chat, source.day, 0, source.slot))
            assertFalse(BackgroundApprovalStore(context).approved(source.image))
        }
    }

    @Test fun wronglyAssociatedChatAssetIsNeverUploadedToTheBackgroundGate(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val source = cached(context, purpose = "chat")
            assertNull(scenes(context, BackgroundImageApproval { error("A chat image cannot enter the generation gate") })
                .get(source.chat, source.day, 0, source.slot))
        }
    }
}
