package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RepoReviewPhotoNativeTest {
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
        }
    }
}
