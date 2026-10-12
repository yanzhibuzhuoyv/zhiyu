package com.zhiyu.app.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/** Exact attachment metadata is queried in a synthetic archive, never a user's archive or image. */
class LocalPhotoDraftRecoveryNativeTest {
    private fun png(): ByteArray = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
        bitmap.eraseColor(0xff876aaa.toInt())
        ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private class Store(val binding: CompanionBinding) : CredentialStore {
        private val connection = SavedConnection(LocalChatRepository.LOCAL_URL, binding.providerId, "synthetic-key", binding.model)
        override fun read() = connection
        override fun readBinding() = binding
        override fun write(connection: SavedConnection) = error("No Key update in a recovery query")
        override fun clear() = error("No logout in a recovery query")
        override fun writeBinding(binding: CompanionBinding) = error("No binding update in a recovery query")
    }

    @Test fun lookupUsesExactUserAttachmentIdsWithinItsOwnerAndNeverReadsOriginalBodies(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            LocalChatDatabase(context).use { archive ->
                val first = archive.ensureConversation("synthetic-owner-a", "synthetic", null)
                val other = archive.ensureConversation("synthetic-owner-b", "synthetic", null)
                val photos = ChatPhotoStore(context); val source = png()
                val userPhoto = photos.importBytes(source).attachment
                val assistantPhoto = photos.importBytes(source).attachment
                val sameBytesNewId = photos.importBytes(source).attachment
                assertEquals(userPhoto.sha256, sameBytesNewId.sha256); assertNotEquals(userPhoto.id, sameBytesNewId.id)
                val sent = archive.append("synthetic-owner-b", other.id, "user", "", attachments = listOf(userPhoto))
                archive.append("synthetic-owner-a", first.id, "assistant", "角色发出的画面", attachments = listOf(assistantPhoto))
                val labelOnly = sameBytesNewId.copy(sourceLabel = userPhoto.id)
                archive.append("synthetic-owner-a", first.id, "user", "重新选的同一张照片", attachments = listOf(labelOnly))
                // A malformed original proves this metadata lookup cannot decode message payloads.
                archive.writableDatabase.execSQL("UPDATE messages SET payload=? WHERE id=?", arrayOf(byteArrayOf(1, 2, 3), sent.id))
                assertTrue(archive.hasUserMediaAttachment("synthetic-owner-b", userPhoto.id))
                assertFalse(archive.hasUserMediaAttachment("synthetic-owner-a", userPhoto.id))
                assertFalse(archive.hasUserMediaAttachment("synthetic-owner-a", assistantPhoto.id))
                assertTrue(archive.hasUserMediaAttachment("synthetic-owner-a", sameBytesNewId.id))
                try { archive.hasUserMediaAttachment("synthetic-owner-a", "../wrong"); fail("Invalid ID cannot reach SQL") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun sentDraftBeyondRecentPageIsNotRestoredButSameShaWithNewIdIsPreserved(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            LocalChatDatabase(context).use { archive ->
                val binding = CompanionBinding("deepseek", "synthetic-bound-model")
                val owner = "companion-" + LocalMemoryCodec.sha((binding.providerId + "\u0000" + binding.model).toByteArray())
                val chat = archive.ensureConversation(owner, binding.model, null)
                val photos = ChatPhotoStore(context); val bytes = png(); val alreadySent = photos.importBytes(bytes)
                photos.saveDraft(alreadySent)
                archive.append(owner, chat.id, "user", "", attachments = listOf(alreadySent.attachment))
                repeat(105) { archive.append(owner, chat.id, "user", "后来的一句话$it") }
                assertTrue(archive.history(owner, chat.id).messages.none { it.mediaAttachments.any { media -> media.id == alreadySent.attachment.id } })
                val repository = LocalChatRepository(context, Store(binding), database = archive, daily = null)
                assertNull("精确证据不能只查最近100条", repository.savedPhotoDraft())
                assertNull(ChatPhotoStore(context).readDraft())
                val newSelection = photos.importBytes(bytes); photos.saveDraft(newSelection)
                assertEquals(alreadySent.attachment.sha256, newSelection.attachment.sha256)
                assertNotEquals(alreadySent.attachment.id, newSelection.attachment.id)
                assertEquals(newSelection, repository.savedPhotoDraft())
                assertEquals(newSelection, ChatPhotoStore(context).readDraft())
            }
        }
    }

    @Test fun freshChatRecordsShareOneBlobWithoutChangingOtherPurposeDedupeOrCacheProtection(): Unit = runBlocking {
        NativeV4Context(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val bytes = png(); val photos = ChatPhotoStore(context); val media = LocalMediaStore(context)
            val first = photos.importBytes(bytes); val second = photos.importBytes(bytes)
            assertNotEquals(first.attachment.id, second.attachment.id)
            assertEquals(first.attachment.sha256, second.attachment.sha256)
            assertArrayEquals(photos.payload(first.attachment).bytes, photos.payload(second.attachment).bytes)
            fun count(folder: String, extension: String) = File(context.filesDir, folder).listFiles { file -> file.extension == extension }!!.size
            assertEquals("Two intentional selections have two attachment records", 2, count("local_media/image", "json"))
            assertEquals("Original bytes must not be stored twice", 1, count("local_media/image/blobs", "bin"))
            assertEquals("Identical normalized derivatives also share bytes", 1, count("chat_photo_derivatives/normalized", "bin"))
            val library = media.ingest("image", bytes, visualVersion = 7)
            assertEquals(library, media.ingest("image", bytes, visualVersion = 7))
            val otherVersion = media.ingest("image", bytes, visualVersion = 8)
            assertNotEquals(library.id, otherVersion.id)
            val portrait = media.ingest("image", bytes, visualVersion = 7, purpose = "portrait")
            assertEquals(portrait, media.ingest("image", bytes, visualVersion = 7, purpose = "portrait"))
            val background = media.ingest("image", bytes, "生成的空场景", visualVersion = 7, dayId = "2026-09-01", purpose = "scene_background")
            assertEquals(background, media.ingest("image", bytes, "生成的空场景", visualVersion = 7, dayId = "2026-09-01", purpose = "scene_background"))
            assertEquals(6, count("local_media/image", "json")); assertEquals(1, count("local_media/image/blobs", "bin"))
            assertEquals(listOf(library), media.list("image", visualVersion = 7))
            val swept = LocalMediaRetention(context, { setOf(first.attachment.id) }).sweep(LocalDate.parse("2026-10-11"))
            assertTrue(swept.failures.toString(), swept.failures.isEmpty())
            assertEquals(0, swept.removedMetadata); assertEquals(0, swept.removedBlobs)
            assertEquals(6, count("local_media/image", "json")); assertEquals(1, count("local_media/image/blobs", "bin"))
            listOf(first.attachment, second.attachment, library, otherVersion, portrait, background).forEach {
                assertArrayEquals(bytes, media.download(it.id, it.sha256))
                assertEquals(it.visualVersion, media.details(it.id).attachment.visualVersion)
            }
        }
    }
}
