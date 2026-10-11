package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.MediaAttachment
import com.zhiyu.app.domain.LocalMediaPlan
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No provider calls, production media, production database or keys are read. */
class LocalMediaRetentionNativeTest {
    private val context = NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext)
    private val media = LocalMediaStore(context)
    private val today = LocalDate.parse("2026-10-10")
    @After fun cleanup() {
        val root = context.filesDir
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.canonicalFile
        check(root.canonicalFile.parentFile == parent && root.name.startsWith("v4_"))
        context.close(); root.deleteRecursively()
    }
    private fun picture(color: Int = 0xff87ab99.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
    private fun stock(day: String? = "2026-10-02", color: Int = 0xff87ab99.toInt()) =
        media.ingest("image", picture(color), "恋人的生活画面", dayId = day, tags = listOf("daily_stock"))
    private fun metadata(asset: MediaAttachment) = File(context.filesDir, "local_media/${asset.kind}/${asset.id}.json")
    private fun blob(asset: MediaAttachment) = File(context.filesDir, "local_media/${asset.kind}/blobs/${asset.sha256}.bin")
    private fun audit() = File(context.filesDir, "local_media/retention_v1.json")

    @Test fun oldUnsentStockAndBackgroundAreRemovedButAllProtectedRecordsStay() {
        val expired = stock()
        val recent = stock("2026-10-03", 0xff111122.toInt())
        val sent = stock("2026-09-01", 0xff222233.toInt())
        val portrait = media.ingest("image", picture(0xff333344.toInt()), "人物原图", dayId = "2026-09-01", purpose = "portrait")
        val user = media.ingest("image", picture(0xff444455.toInt()), "用户图片", dayId = "2026-09-01", purpose = "chat")
        val sticker = media.ingest("sticker", picture(0xff555566.toInt()), "内置素材", dayId = "2026-09-01")
        val background = media.ingest("image", picture(0xff666677.toInt()), "生成的空场景", dayId = "2026-09-01", purpose = "scene_background")
        val undated = media.ingest("image", picture(0xff777788.toInt()), "生成的空场景", purpose = "scene_background")
        val result = LocalMediaRetention(context, { setOf(sent.id) }).sweep(today)
        assertTrue(result.failures.toString(), result.failures.isEmpty()); assertTrue(result.auditSaved)
        assertEquals(2, result.removedMetadata); assertEquals(2, result.removedBlobs)
        assertTrue(result.freedBytes > 0); assertFalse(metadata(expired).exists()); assertFalse(blob(background).exists())
        listOf(recent, sent, portrait, user, sticker, undated).forEach { assertArrayEquals(pictureFor(it), media.download(it.id, it.sha256)) }
        assertEquals(0, LocalMediaRetention(context, { setOf(sent.id) }).sweep(today).removedMetadata)
    }
    private fun pictureFor(asset: MediaAttachment): ByteArray = blob(asset).readBytes()

    @Test fun sharedBlobSurvivesPinnedAndCrossKindReferences() {
        val expired = stock()
        val protected = media.ingest("image", picture(), "人物原图", purpose = "portrait")
        val sticker = media.ingest("sticker", picture(), "内置素材")
        val result = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertEquals(1, result.removedMetadata); assertEquals(0, result.removedBlobs)
        assertTrue(blob(expired).isFile); assertTrue(blob(sticker).isFile)
        assertArrayEquals(picture(), media.download(protected.id))
    }
    @Test fun unreadablePinsAbortAndRecordAnExplicitFailureWithoutDeleting() {
        val expired = stock()
        val result = LocalMediaRetention(context, { throw IllegalStateException("synthetic pins failure") }).sweep(today)
        assertEquals(0, result.removedMetadata); assertEquals("pins", result.failures.single().phase)
        assertTrue(result.auditSaved); assertTrue(metadata(expired).isFile); assertTrue(blob(expired).isFile)
        assertEquals("pins", JSONObject(audit().readText()).getJSONObject("last_result").getJSONArray("failures").getJSONObject(0).getString("phase"))
    }
    @Test fun malformedMetadataAndInterruptedSidecarsFailClosed() {
        val expired = stock()
        val broken = File(context.filesDir, "local_media/image/${"f".repeat(32)}.json")
        broken.writeText("not json")
        val first = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertEquals("metadata", first.failures.single().phase); assertTrue(metadata(expired).isFile)
        check(broken.delete())
        val sidecar = File(metadata(expired).path + ".bak").apply { writeText("interrupted") }
        assertEquals("metadata", LocalMediaRetention(context, { emptySet() }).sweep(today).failures.single().phase)
        assertTrue(sidecar.isFile); assertTrue(blob(expired).isFile)
    }
    @Test fun metadataDeleteFailureKeepsItsBlobAndPersistsRetryIntent() {
        val expired = stock()
        val failed = LocalMediaRetention(context, { emptySet() }, deleteFile = { file -> if (file.extension == "json") false else file.delete() }).sweep(today)
        assertEquals("metadata_delete", failed.failures.single().phase)
        assertTrue(metadata(expired).isFile); assertTrue(blob(expired).isFile); assertEquals(1, failed.pendingRecords)
        val retried = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertTrue(retried.failures.isEmpty()); assertEquals(1, retried.removedMetadata); assertEquals(1, retried.removedBlobs)
        assertEquals(0, retried.pendingRecords)
    }
    @Test fun blobDeleteFailureRecoversAfterReloadWithoutScanningUnknownOrphans() {
        val expired = stock()
        val orphan = File(blob(expired).parentFile, "${"d".repeat(64)}.bin").apply { writeBytes(picture()) }
        val failed = LocalMediaRetention(context, { emptySet() }, deleteFile = { file -> if (file.extension == "bin") false else file.delete() }).sweep(today)
        assertEquals(1, failed.removedMetadata); assertEquals("blob_delete", failed.failures.single().phase)
        assertFalse(metadata(expired).exists()); assertTrue(blob(expired).isFile)
        val retried = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertEquals(0, retried.removedMetadata); assertEquals(1, retried.removedBlobs); assertEquals(0, retried.pendingRecords)
        assertTrue(orphan.isFile)
    }
    @Test fun interruptedIntentDoesNotDeleteARecentlyPinnedAssetOrItsSharedBlob() {
        val expired = stock()
        val target = JSONObject().put("id", expired.id).put("kind", expired.kind).put("sha256", expired.sha256)
            .put("day", expired.dayId).put("source", expired.sourceLabel).put("purpose", "library")
            .put("tags", JSONArray().put("daily_stock"))
        audit().writeText(JSONObject().put("schema", 1).put("pending", JSONArray().put(target)).toString())
        val result = LocalMediaRetention(context, { setOf(expired.id) }).sweep(today)
        assertEquals(0, result.removedMetadata); assertEquals(0, result.removedBlobs)
        assertTrue(metadata(expired).isFile); assertTrue(blob(expired).isFile); assertEquals(0, result.pendingRecords)
    }
    @Test fun invalidJournalDoesNotOverwriteRecoveryInformationOrDeleteAssets() {
        val expired = stock(); audit().writeText("broken intent")
        val result = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertEquals("journal", result.failures.single().phase); assertFalse(result.auditSaved)
        assertEquals("broken intent", audit().readText()); assertTrue(metadata(expired).isFile)
    }
    @Test fun metadataPinsAndBlobCommitUseTheSameIngestLock() {
        val expired = stock()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val ingestStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sweep = pool.submit<LocalMediaRetentionResult> { LocalMediaRetention(context, {
                check(Thread.holdsLock(LocalMediaStore.lock)); entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)); emptySet()
            }).sweep(today) }
            check(entered.await(3, TimeUnit.SECONDS))
            val ingest = pool.submit<MediaAttachment> { ingestStarted.countDown(); stock("2026-10-10", 0xff123456.toInt()) }
            check(ingestStarted.await(3, TimeUnit.SECONDS)); assertFalse(ingest.isDone)
            release.countDown()
            assertEquals(1, sweep.get(3, TimeUnit.SECONDS).removedMetadata)
            assertTrue(metadata(ingest.get(3, TimeUnit.SECONDS)).isFile); assertFalse(metadata(expired).exists())
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun slowStaleAttachmentCannotCreateAReferenceAfterItsCacheWasSwept() {
        val expired = stock()
        LocalChatDatabase(context).use { database ->
            val owner = "synthetic-retention-owner"
            val conversation = database.ensureConversation(owner, "synthetic-model", null)
            val result = LocalMediaRetention(context, { database.pinnedMediaIds() }).sweep(today)
            assertEquals(1, result.removedMetadata)
            try {
                database.append(owner, conversation.id, "assistant", "这是已过期的慢请求", attachments = listOf(expired))
                fail("A stale cached attachment must not be committed")
            } catch (_: IllegalArgumentException) { }
            assertTrue(database.pinnedMediaIds().isEmpty())
            assertTrue(database.history(owner, conversation.id).messages.isEmpty())
        }
    }

    @Test fun aLateMediaSelectionKeepsTheNaturalReplyAfterItsCacheWasSwept() {
        val expired = stock()
        val chat = LocalMediaStore.identifier(); val nonce = LocalMediaStore.identifier()
        val plan = LocalMediaPlan("", listOf(expired), nonce)
        val result = LocalMediaRetention(context, { emptySet() }).sweep(today)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        val reply = LocalReplyMedia(context).commit(chat, plan, "今天的光真好。\n[[ZHIYU_MEDIA_$nonce:${expired.id}]]") { it }
        assertEquals("今天的光真好。", reply.content)
        assertTrue(reply.attachments.isEmpty())
    }
}
