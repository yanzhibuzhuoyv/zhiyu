package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test

class RepoReviewPhotoNativeTest {
    @Test fun concurrentSelectorsCannotDeliverTheSamePhotoTwice() = runBlocking {
        DailyShareNativeFixture().use { f -> LocalChatDatabase(f.context).use { archive ->
            check(f.context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
            f.image(labels = listOf("cat"), day = null)
            val owner = "synthetic-concurrent-photo"
            val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
            val selectors = List(2) { LocalReplyMedia(f.context, archive) { f.now } }
            val plans = selectors.map { it.prepare(chat.id, null, emptyList(), "给我发张猫咪照片") }
            val replies = selectors.zip(plans).map { (media, plan) -> async(Dispatchers.Default) {
                media.commit(chat.id, plan, "给你看") { reply ->
                    archive.append(owner, chat.id, "assistant", reply.content, attachments = reply.attachments)
                }
            } }.awaitAll()
            assertEquals(1, replies.sumOf { it.mediaAttachments.size })
            assertEquals(1, archive.history(owner, chat.id).messages.sumOf { it.mediaAttachments.size })
        } }
    }
    @Test fun aFailureAfterTheChatInsertRollsBackBothStoresAndASuccessSurvivesReopen() = runBlocking {
        DailyShareNativeFixture().use { f ->
            check(f.context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
            val image = f.image(labels = listOf("cat"), day = null)
            val owner = "synthetic-atomic-photo"
            val chat = LocalChatDatabase(f.context).use { archive ->
                val chat = archive.ensureConversation(owner, "synthetic-model", CompanionProfile())
                val media = LocalReplyMedia(f.context, archive) { f.now }
                val plan = media.prepare(chat.id, null, emptyList(), "给我发张猫咪照片")
                assertThrows(IllegalStateException::class.java) {
                    media.commit(chat.id, plan, "给你看") { reply ->
                        archive.append(owner, chat.id, "assistant", reply.content, attachments = reply.attachments)
                        error("synthetic failure after append")
                    }
                }
                assertTrue(archive.history(owner, chat.id).messages.isEmpty())
                assertEquals(image.id, media.prepare(chat.id, null, emptyList(), "给我发张猫咪照片").requiredAttachmentId)
                media.commit(chat.id, plan, "给你看") { reply ->
                    archive.append(owner, chat.id, "assistant", reply.content, attachments = reply.attachments)
                }
                chat.id
            }
            LocalChatDatabase(f.context).use { reopened ->
                assertEquals(image.id, reopened.history(owner, chat).messages.single().mediaAttachments.single().id)
                val fresh = LocalReplyMedia(f.context, reopened) { f.now }
                assertNull(fresh.prepare(chat, null, emptyList(), "给我发张猫咪照片").requiredAttachmentId)
            }
        }
    }

    @Test fun migrationKeepsOldDeliveryEvidenceAndDoesNotResendOldPhotos() = runBlocking {
        DailyShareNativeFixture().use { f ->
            check(f.context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
            val image = f.image(labels = listOf("cat"), day = null)
            check(f.context.getSharedPreferences("photo_delivery_used_sha_v1", 0).edit()
                .putStringSet("chat_${f.chat}", setOf(image.sha256)).commit())
            assertNull(LocalReplyMedia(f.context) { f.now }.prepare(f.chat, null, emptyList(), "给我发张猫咪照片").requiredAttachmentId)
            // Legacy data is kept intact; migration completion and all SHA rows are atomic in SQLite.
            assertEquals(setOf(image.sha256), f.context.getSharedPreferences("photo_delivery_used_sha_v1", 0).getStringSet("chat_${f.chat}", null))
        }
    }
    @Test fun failedChatSaveMustNotConsumeAnUnsentPhoto() = runBlocking {
        DailyShareNativeFixture().use { f ->
            check(f.context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
            val image = f.image(labels = listOf("cat"), day = null)
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张猫咪照片")
            assertEquals(image.id, plan.requiredAttachmentId)
            assertThrows(IllegalStateException::class.java) {
                selector.commit(f.chat, plan, "给你看看") { throw IllegalStateException("synthetic disk failure before append") }
            }
            val restored = LocalReplyMedia(f.context) { f.now }
            val retry = restored.prepare(f.chat, null, emptyList(), "给我发张猫咪照片")
            assertEquals("Unsent image must remain available after a failed chat save", image.id, retry.requiredAttachmentId)
        }
    }

    @Test fun oldDeliveryLedgerMustNotDisableAllFutureNewPhotos() = runBlocking {
        DailyShareNativeFixture().use { f ->
            check(f.context.getSharedPreferences("native_media_seed", 0).edit().putInt("version", 2).commit())
            val image = f.image(labels = listOf("cat"), day = null)
            val used = (0 until 4096).map { LocalMemoryCodec.sha("old-photo-$it".toByteArray()) }.toSet()
            assertFalse(image.sha256 in used)
            check(f.context.getSharedPreferences("photo_delivery_used_sha_v1", 0).edit().putStringSet("chat_${f.chat}", used).commit())
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList(), "给我发张猫咪照片")
            assertEquals("Old SHA ledger cannot disqualify a never-sent valid photo", image.id, plan.requiredAttachmentId)
            assertEquals(image.id, selector.commit(f.chat, plan, "给你看") { it }.attachments.single().id)
            val next = f.image(labels = listOf("cat"), day = null, color = 0xff294199.toInt())
            val reopened = LocalReplyMedia(f.context) { f.now }
            assertEquals(next.id, reopened.prepare(f.chat, null, emptyList(), "给我发张猫咪照片").requiredAttachmentId)
        }
    }
}
