package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.CompanionProfile
import com.zhiyu.app.domain.MediaAttachment
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Isolated old-version migration fixtures only; never accesses production media, keys or cloud. */
class StickerLibraryRetirementNativeTest {
    private val context = NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext)
    private val media = LocalMediaStore(context)
    @After fun close() {
        val folder = context.filesDir
        val anchor = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.canonicalFile
        check(folder.canonicalFile.parentFile == anchor && folder.name.startsWith("v4_"))
        context.close(); folder.deleteRecursively()
    }

    @Test fun newStickerImportsAreRejectedWhileOrdinaryImagesStillWork() {
        assertThrows(IllegalArgumentException::class.java) { media.ingest("sticker", png()) }
        val image = media.ingest("image", png())
        assertArrayEquals(png(), media.download(image.id, image.sha256))
    }

    @Test fun unreferencedOldLibraryIsRemovedButAllOwnersHistoryAndSharedPixelsSurvive() {
        val unused = legacySticker(context, png(0xff111111.toInt()))
        val sent = legacySticker(context, png(0xff222222.toInt()))
        val otherOwnerSent = legacySticker(context, png(0xff333333.toInt()))
        val sharedUnused = legacySticker(context, png(0xff222222.toInt()))
        val image = media.ingest("image", png(0xff444444.toInt()), purpose = "portrait")
        LocalChatDatabase(context).use { database ->
            listOf("owner-one" to sent, "owner-two" to otherOwnerSent).forEach { (owner, sticker) ->
                val chat = database.ensureConversation(owner, "synthetic-model", null)
                database.append(owner, chat.id, "assistant", "旧版已经发送的表情", attachments = listOf(sticker))
            }
            val result = StickerLibraryRetirement(context, database::pinnedMediaIds).retire()
            assertEquals(2, result.removedRecords); assertEquals(1, result.removedBlobs)
            assertFalse(metadata(unused).exists()); assertFalse(blob(unused).exists()); assertFalse(metadata(sharedUnused).exists())
            listOf(sent, otherOwnerSent, image).forEach { assertTrue(metadata(it).isFile); assertArrayEquals(blob(it).readBytes(), media.download(it.id, it.sha256)) }
            assertTrue(media.list("sticker").isEmpty())
            assertEquals(0, StickerLibraryRetirement(context, database::pinnedMediaIds).retire().removedRecords)
        }
    }

    @Test fun unreadableAllOwnerHistoryAbortsBeforeDeletingAnyOriginal() {
        val unused = legacySticker(context, png())
        assertThrows(IllegalStateException::class.java) {
            StickerLibraryRetirement(context) { throw IllegalStateException("synthetic unreadable pins") }.retire()
        }
        assertTrue(metadata(unused).isFile); assertTrue(blob(unused).isFile)
    }

    @Test fun corruptLegacyMetadataAbortsTheWholePlanBeforeDeletingTheHealthyUnusedAsset() {
        val unused = legacySticker(context, png())
        val bad = File(context.filesDir, "local_media/sticker/${"f".repeat(32)}.json")
        bad.writeText("broken metadata")
        assertThrows(Exception::class.java) { StickerLibraryRetirement(context) { emptySet() }.retire() }
        assertTrue(metadata(unused).isFile); assertTrue(blob(unused).isFile); assertEquals("broken metadata", bad.readText())
    }

    @Test fun aSavedRetirementResumesItsKnownOrphanButNeverDeletesAnUnknownFile() {
        val unused = legacySticker(context, png())
        val orphan = File(blob(unused).parentFile, "${"d".repeat(64)}.bin").apply { writeBytes(png(0xff123456.toInt())) }
        val intent = JSONObject().put("schema", 2).put("pending", JSONArray().put(
            JSONObject().put("id", unused.id).put("sha256", unused.sha256)))
        LocalMediaStore.write(File(context.filesDir, "local_media/sticker_retirement_v2.json"), intent.toString().toByteArray())
        check(metadata(unused).delete()) // Exact crash checkpoint: intent committed, metadata removed, blob still present.
        StickerLibraryRetirement(context) { emptySet() }.retire()
        assertFalse(blob(unused).exists()); assertTrue(orphan.isFile)
    }

    @Test fun corruptRecoveryIntentIsNeverOverwrittenAndPreventsDeletion() {
        val unused = legacySticker(context, png())
        val journal = File(context.filesDir, "local_media/sticker_retirement_v2.json").apply { writeText("broken intent") }
        assertThrows(Exception::class.java) { StickerLibraryRetirement(context) { emptySet() }.retire() }
        assertEquals("broken intent", journal.readText()); assertTrue(metadata(unused).exists()); assertTrue(blob(unused).exists())
    }

    @Test fun versionOneSeedUpgradeRetiresUnsentLibraryAndDoesNotReimportIt() {
        val unused = legacySticker(context, png())
        val prefs = context.getSharedPreferences("native_media_seed", 0)
        check(prefs.edit().putInt("version", 1).commit())
        NativeSeedImporter(context).install()
        assertEquals(2, prefs.getInt("version", 0))
        assertFalse(metadata(unused).exists()); assertTrue(media.list("sticker").isEmpty())
        assertEquals(2, media.list("image").size)
        NativeSeedImporter(context).install()
        assertTrue(media.list("sticker").isEmpty()); assertEquals(2, media.list("image").size)
    }

    @Test fun damagedLegacyLibraryDoesNotDisableOrdinaryChatSeedImagesOrPretendCleanupSucceeded() {
        val bad = File(context.filesDir, "local_media/sticker/${"f".repeat(32)}.json")
        check(bad.parentFile!!.mkdirs()); bad.writeText("broken metadata")
        NativeSeedImporter(context).install()
        val prefs = context.getSharedPreferences("native_media_seed", 0)
        assertEquals(1, prefs.getInt("version", 0)); assertTrue(prefs.getBoolean("retirement_pending", false))
        assertTrue(prefs.getString("retirement_issue", null).orEmpty().isNotBlank())
        assertEquals(2, media.list("image").size); assertEquals("broken metadata", bad.readText())
        assertTrue(media.list("sticker").isEmpty())
    }

    @Test fun packagedSeedManifestContainsOnlyTheTwoRetainedImages() {
        val manifest = context.assets.open("media_seed/manifest.json").use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
        assertEquals(2, manifest.length())
        (0 until manifest.length()).forEach { assertEquals("image", manifest.getJSONObject(it).getString("kind")) }
        val files = checkNotNull(context.assets.list("media_seed")).toSet()
        assertEquals((0 until manifest.length()).map { manifest.getJSONObject(it).getString("file") }.toSet() + "manifest.json", files)
    }

    @Test fun pinnedOldStickerCannotBecomeANewReplyCandidate() = runBlocking {
        val old = legacySticker(context, png())
        check(context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
        val plan = LocalReplyMedia(context).prepare(LocalMediaStore.identifier(), CompanionProfile(), emptyList())
        assertTrue(plan.candidates.none { it.kind == "sticker" })
        assertFalse(plan.prompt.contains("表情"))
        assertArrayEquals(png(), media.download(old.id, old.sha256))
    }

    private fun metadata(asset: MediaAttachment) = File(context.filesDir, "local_media/${asset.kind}/${asset.id}.json")
    private fun blob(asset: MediaAttachment) = File(context.filesDir, "local_media/${asset.kind}/blobs/${asset.sha256}.bin")
    private fun png(color: Int = 0xffabcdef.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
