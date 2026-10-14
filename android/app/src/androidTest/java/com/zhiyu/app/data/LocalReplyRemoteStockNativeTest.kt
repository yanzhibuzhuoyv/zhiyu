package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Synthetic local pixels in an isolated sandbox; no AI call or actual credential is involved. */
class LocalReplyRemoteStockNativeTest {
    private fun remoteTags(f: DailyShareNativeFixture) = f.tags.filter { it != "qwen_generated" } +
        listOf("remote_generated", "remote_chat_${f.chat.take(24)}")
    private fun choose(media: LocalReplyMedia, chat: String, plan: LocalMediaPlan, image: MediaAttachment) =
        media.commit(chat, plan, "原回复完整保留。\n[[ZHIYU_MEDIA_${plan.nonce}:${image.id}]]") { it }

    @Test fun remoteSelectionRequiresChatReviewedDecodedAndActualTrajectoryWithoutPlaceholder() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val tags = remoteTags(f)
            val valid = f.image(labels = tags)
            val unreviewed = f.image(labels = tags - "composite_reviewed")
            val undecoded = f.image(labels = tags - "decoded_final")
            val otherChat = f.image(labels = tags.filterNot { it.startsWith("remote_chat_") } + "remote_chat_${"f".repeat(24)}")
            val otherTrajectory = f.image(labels = tags.filterNot { it.startsWith("trajectory_") } + "trajectory_${"f".repeat(24)}")
            val placeholder = f.image(labels = tags + "local_placeholder")
            val selector = LocalReplyMedia(f.context) { f.now }
            val ids = selector.prepare(f.chat, null, emptyList()).candidates.map { it.id }
            assertTrue(valid.id in ids)
            listOf(unreviewed, undecoded, otherChat, otherTrajectory, placeholder).forEach { assertFalse(it.id in ids) }
        }
    }

    @Test fun changedPersistedTrajectoryRejectsPreviouslyOfferedRemoteStockAtCommit() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val image = f.image(labels = remoteTags(f))
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList())
            assertTrue(plan.candidates.any { it.id == image.id })
            f.changePersistedActivity()
            val reply = choose(selector, f.chat, plan, image)
            assertTrue(reply.attachments.isEmpty()); assertEquals("原回复完整保留。", reply.content)
        }
    }

    @Test fun removedReviewAfterOfferingCannotBypassCommitGate() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val image = f.image(labels = remoteTags(f))
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList())
            f.rewriteMetadata(image) { it.put("tags", JSONArray(remoteTags(f) - "composite_reviewed")) }
            val reply = choose(selector, f.chat, plan, image)
            assertTrue(reply.attachments.isEmpty()); assertEquals("原回复完整保留。", reply.content)
        }
    }

    @Test fun oldReviewedQwenStockAndOrdinaryOriginalLibraryRemainCompatible() = runBlocking {
        DailyShareNativeFixture().use { f ->
            val old = f.image()
            val original = f.image(labels = listOf("food"), day = null, color = 0xff785431.toInt())
            val selector = LocalReplyMedia(f.context) { f.now }
            val plan = selector.prepare(f.chat, null, emptyList())
            assertTrue(plan.candidates.any { it.id == old.id }); assertTrue(plan.candidates.any { it.id == original.id })
            listOf(old, original).forEach { image ->
                assertEquals(image.id, choose(selector, f.chat, plan, image).attachments.single().id)
            }
        }
    }
}
