package com.zhiyu.app.data.remotescene

import android.graphics.Bitmap
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.data.NativeV4Context
import com.zhiyu.app.domain.ChatPhotoRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All files, symlink targets and pixels belong to one isolated test context. No model or Key is read. */
class RemoteSceneCandidateLifecycleNativeTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = "remote_scene_lifecycle_test"
    private fun isolated(test: (NativeV4Context, File) -> Unit) = NativeV4Context(base).use { context ->
        val root = File(context.filesDir, directory)
        try { test(context, root) } finally { context.filesDir.deleteRecursively() }
    }
    private fun pixels(noise: Boolean = false): ByteArray {
        val side = if (noise) 512 else 32
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        return try {
            if (noise) {
                val random = Random(827L)
                val colors = IntArray(side * side) { 0xff000000.toInt() or random.nextInt(0x1000000) }
                bitmap.setPixels(colors, 0, side, 0, 0, side, side)
            } else bitmap.eraseColor(0xff4268ab.toInt())
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun candidate(bytes: ByteArray) = RemoteSceneCandidate("a".repeat(32), "b".repeat(32), "c".repeat(64),
        ChatPhotoRules.sha(bytes), "image/png", "Same original OC at the window.", 0, "2026-10-14", "08:00", "d".repeat(64),
        originalId = "e".repeat(32), originalSha = "f".repeat(64), visualVersion = 3, packageSha = "1".repeat(64))
    private fun snapshots(root: File) = root.listFiles().orEmpty().associate { it.name to ChatPhotoRules.sha(it.readBytes()) }

    @Test fun lifecycleMetadataRoundTripsAndLegacyRecordsDefaultWithoutAcceptingWrongTypes() = isolated { context, root ->
        val bytes = pixels(); val value = candidate(bytes).copy(retryOf = "2".repeat(32), reviewBindingSha = "3".repeat(64))
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        assertEquals(value, RemoteSceneCandidateStore(context, directory).read(value.requestId))
        val file = File(root, "${value.requestId}.json")
        val legacy = JSONObject(file.readText()).apply { remove("pixelsRemoved"); remove("retryOf"); remove("reviewBindingSha") }
        file.writeText(legacy.toString())
        assertEquals(value.copy(retryOf = null, reviewBindingSha = null), store.read(value.requestId))
        file.writeText(legacy.put("pixelsRemoved", "false").toString())
        assertThrows(IllegalArgumentException::class.java) { store.read(value.requestId) }
    }

    @Test fun reviewBindingMayChangeWhileOriginalBindingPixelsAndRetryLineageCannot() = isolated { context, _ ->
        val bytes = pixels(); val value = candidate(bytes).copy(retryOf = "2".repeat(32))
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        val reviewed = value.copy(status = LifePhotoReviewStatus.APPROVED, reason = "synthetic_review", reviewBindingSha = "3".repeat(64))
        store.write(reviewed, bytes)
        assertEquals(reviewed, store.read(value.requestId)); assertArrayEquals(bytes, store.bytes(reviewed))
        listOf(reviewed.copy(bindingSha = "4".repeat(64)), reviewed.copy(retryOf = null), reviewed.copy(originalSha = "5".repeat(64)),
            reviewed.copy(chat = "6".repeat(32)), reviewed.copy(pixelsRemoved = true), reviewed.copy(reviewBindingSha = "invalid"),
            reviewed.copy(retryOf = reviewed.requestId)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.write(invalid, bytes) }
        }
        assertEquals(reviewed, store.read(value.requestId))
    }

    @Test fun clearingPersistsTombstoneRemovesOwnPixelFilesAndNeverRevivesOrChangesPublishedMedia() = isolated { context, root ->
        val bytes = pixels(); val value = candidate(bytes)
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        val library = LocalMediaStore(context)
        val published = library.ingest("image", bytes, "synthetic already published", 3, value.day, "synthetic", emptyList())
        File(root, "${value.requestId}.bin.bak").writeBytes(bytes)
        File(root, "${value.requestId}.bin.new").writeBytes(bytes)
        val cleared = store.clearPixels(value.chat, value.requestId, value.imageSha)
        assertEquals(value.copy(pixelsRemoved = true), cleared)
        assertEquals(cleared, RemoteSceneCandidateStore(context, directory).read(value.requestId))
        assertTrue(File(root, "${value.requestId}.json").isFile)
        listOf("bin", "bin.bak", "bin.new").forEach { assertFalse(File(root, "${value.requestId}.$it").exists()) }
        assertThrows(IllegalArgumentException::class.java) { store.bytes(value) }
        assertThrows(IllegalArgumentException::class.java) { store.bytes(cleared) }
        assertThrows(IllegalArgumentException::class.java) { store.write(value, bytes) }
        assertThrows(IllegalArgumentException::class.java) { store.write(cleared, bytes) }
        assertEquals(cleared, store.clearPixels(value.chat, value.requestId, value.imageSha))
        assertArrayEquals(bytes, library.download(published.id, published.sha256))
    }

    @Test fun wrongChatShaAndTraversalNeverModifyAnyCandidateFile() = isolated { context, root ->
        val bytes = pixels(); val value = candidate(bytes)
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        val before = snapshots(root)
        assertThrows(IllegalArgumentException::class.java) { store.clearPixels("7".repeat(32), value.requestId, value.imageSha) }
        assertThrows(IllegalArgumentException::class.java) { store.clearPixels(value.chat, value.requestId, "8".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { store.clearPixels(value.chat, "../outside", value.imageSha) }
        assertEquals(before, snapshots(root)); assertEquals(value, store.read(value.requestId))
        assertArrayEquals(bytes, store.bytes(value))
    }

    @Test fun aDurableTombstoneStillBlocksBytesAfterACrashBeforeDeletingThePixelFiles() = isolated { context, root ->
        val bytes = pixels(); val value = candidate(bytes)
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        val metadata = File(root, "${value.requestId}.json")
        metadata.writeText(JSONObject(metadata.readText()).put("pixelsRemoved", true).toString())
        File(root, "${value.requestId}.bin.bak").writeBytes(bytes)
        File(root, "${value.requestId}.bin.new").writeBytes(bytes)
        val reopened = RemoteSceneCandidateStore(context, directory)
        val removed = requireNotNull(reopened.read(value.requestId))
        assertTrue(removed.pixelsRemoved)
        assertThrows(IllegalArgumentException::class.java) { reopened.bytes(removed) }
        assertThrows(IllegalArgumentException::class.java) { reopened.write(value, bytes) }
        assertEquals(removed, reopened.clearPixels(value.chat, value.requestId, value.imageSha))
        assertEquals(setOf("${value.requestId}.json"), root.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun symlinkedPrivateDirectoryOrPixelBackupCannotDeleteOrModifyItsTarget() = isolated { context, root ->
        val outside = File(context.filesDir, "synthetic_outside").apply { check(mkdirs()) }
        val marker = File(outside, "marker").apply { writeText("retain synthetic outside") }
        Os.symlink(outside.absolutePath, root.absolutePath)
        try { assertThrows(IllegalArgumentException::class.java) { RemoteSceneCandidateStore(context, directory) } }
        finally { check(root.delete()) }
        assertEquals("retain synthetic outside", marker.readText())
        val bytes = pixels(); val value = candidate(bytes)
        val store = RemoteSceneCandidateStore(context, directory); store.write(value, bytes)
        val backup = File(root, "${value.requestId}.bin.bak")
        Os.symlink(marker.absolutePath, backup.absolutePath)
        val metadata = File(root, "${value.requestId}.json").readText()
        try {
            assertThrows(IllegalArgumentException::class.java) { store.clearPixels(value.chat, value.requestId, value.imageSha) }
            assertEquals(metadata, File(root, "${value.requestId}.json").readText())
            assertEquals("retain synthetic outside", marker.readText())
        } finally { check(backup.delete()) }
    }

    @Test fun clearedPixelSpaceIsReclaimedWhileMetadataAndUnrelatedFilesStayInTheBudget() = isolated { context, root ->
        val bytes = pixels(noise = true); assertTrue(bytes.size > 8192)
        val first = candidate(bytes); val second = first.copy(requestId = "2".repeat(32), retryOf = first.requestId)
        val store = RemoteSceneCandidateStore(context, directory); store.write(first, bytes)
        val retained = File(root, "${"9".repeat(32)}.bin")
        RandomAccessFile(retained, "rw").use { it.setLength(128L * 1024 * 1024 - bytes.size - 8192) }
        val retainedLength = retained.length()
        assertThrows(IllegalArgumentException::class.java) { store.write(second, bytes) }
        assertNull(store.read(second.requestId))
        store.clearPixels(first.chat, first.requestId, first.imageSha)
        store.write(second, bytes)
        assertEquals(second, store.read(second.requestId))
        assertEquals(retainedLength, retained.length()); assertTrue(store.read(first.requestId)!!.pixelsRemoved)
        assertTrue(File(root, "${first.requestId}.json").isFile)
    }

    @Test fun concurrentReviewAndClearAlwaysEndInAPersistentNonRevivingTombstone() = isolated { context, root ->
        val bytes = pixels(); val value = candidate(bytes)
        val first = RemoteSceneCandidateStore(context, directory); first.write(value, bytes)
        val second = RemoteSceneCandidateStore(context, directory)
        val executor = Executors.newFixedThreadPool(2); val ready = CountDownLatch(2); val start = CountDownLatch(1)
        try {
            val clear = executor.submit<RemoteSceneCandidate> {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS)); first.clearPixels(value.chat, value.requestId, value.imageSha)
            }
            val review = executor.submit<Boolean> {
                ready.countDown(); check(start.await(10, TimeUnit.SECONDS))
                try { second.write(value.copy(status = LifePhotoReviewStatus.APPROVED, reason = "synthetic_review", reviewBindingSha = "3".repeat(64)), bytes); true }
                catch (_: IllegalArgumentException) { false }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); clear.get(10, TimeUnit.SECONDS); review.get(10, TimeUnit.SECONDS)
            val saved = requireNotNull(second.read(value.requestId)); assertTrue(saved.pixelsRemoved)
            assertThrows(IllegalArgumentException::class.java) { second.bytes(saved) }
            assertThrows(IllegalArgumentException::class.java) { second.write(value, bytes) }
            assertFalse(File(root, "${value.requestId}.bin").exists()); assertTrue(File(root, "${value.requestId}.json").isFile)
        } finally { start.countDown(); executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun finalLocalPublicationAndClearUseTheSameShortLockAcrossStoreInstances() = isolated { context, _ ->
        val bytes = pixels(); val value = candidate(bytes)
        val first = RemoteSceneCandidateStore(context, directory); first.write(value, bytes)
        val second = RemoteSceneCandidateStore(context, directory)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val clearing = CountDownLatch(1)
        var publications = 0
        try {
            val delivery = executor.submit<Int> { first.withCurrentCandidate(value) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); ++publications
            } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val clear = executor.submit<RemoteSceneCandidate> {
                clearing.countDown(); second.clearPixels(value.chat, value.requestId, value.imageSha)
            }
            assertTrue(clearing.await(10, TimeUnit.SECONDS)); assertFalse(clear.isDone)
            release.countDown(); assertEquals(1, delivery.get(10, TimeUnit.SECONDS).toInt())
            assertTrue(clear.get(10, TimeUnit.SECONDS).pixelsRemoved)
            assertThrows(IllegalArgumentException::class.java) { second.withCurrentCandidate(value) { publications++ } }
            assertEquals(1, publications)
        } finally { release.countDown(); executor.shutdownNow(); check(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
