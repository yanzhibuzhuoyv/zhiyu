package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalMediaProtocolTest {
    private val id = "a".repeat(32)
    private val asset = MediaAttachment(id, "sticker", MediaRules.path(id), "image/png", "b".repeat(64), null, 0, "test")
    private val plan = LocalMediaPlan("", listOf(asset), "fresh")
    @Test fun aFreshAllowlistedFinalMarkerSelectsOneImageAndNeverEntersSavedText() {
        val result = plan.parse("嗯，过来抱抱。\n[[ZHIYU_MEDIA_fresh:$id]]")
        assertEquals("嗯，过来抱抱。", result.content)
        assertEquals(listOf(asset), result.attachments)
    }
    @Test fun staleUnknownDuplicatedAndInlineMarkersNeverSelectAssets() {
        for (value in listOf("[[ZHIYU_MEDIA_old:$id]]", "[[ZHIYU_MEDIA_fresh:${"c".repeat(32)}]]",
            "[[ZHIYU_MEDIA_fresh:$id]]\n[[ZHIYU_MEDIA_fresh:$id]]", "[[ZHIYU_MEDIA_fresh:$id]]继续说")) {
            val result = plan.parse("我在呢。\n$value")
            assertTrue(result.attachments.isEmpty())
            assertFalse(result.content.contains("ZHIYU_MEDIA_"))
        }
    }
    @Test fun choosingNoImagePreservesTheCompleteLongReply() {
        val content = "今天想听你慢慢说。".repeat(1000)
        assertEquals(content, plan.parse(content).content)
    }
    @Test fun malformedAndEmptyControlRepliesAreRejectedOrCleaned() {
        assertEquals("你好", plan.parse("你好[[ZHIYU_MEDIA_bad").content)
        assertThrows(IllegalArgumentException::class.java) { plan.parse("[[ZHIYU_MEDIA_fresh:$id]]") }
    }

    @Test fun aChangedOcDropsOnlyTheLateAttachmentAndKeepsEveryReplyCharacter() {
        val person = asset.copy(kind = "image", visualVersion = 1, dayId = "2026-10-09")
        val content = "慢慢同我说吧。".repeat(1000)
        val reply = LocalMediaReply(content, listOf(person))
        assertEquals(content, LocalMediaFreshness.accept(reply, 2, "2026-10-09", setOf(id)).content)
        assertTrue(LocalMediaFreshness.accept(reply, 2, "2026-10-09", setOf(id)).attachments.isEmpty())
        assertEquals(listOf(person), LocalMediaFreshness.accept(reply, 1, "2026-10-09", setOf(id)).attachments)
        assertTrue(LocalMediaFreshness.accept(reply, 0, "2026-10-09", setOf(id)).attachments.isEmpty())
    }

    @Test fun yesterdayCannotBeAttachedAfterMidnightButNonpersonImagesSurviveAnOcChange() {
        val image = asset.copy(kind = "image", visualVersion = 0, dayId = "2026-10-09")
        val reply = LocalMediaReply("完整正文", listOf(image))
        assertTrue(LocalMediaFreshness.accept(reply, 2, "2026-10-10", emptySet()).attachments.isEmpty())
        assertEquals(listOf(image), LocalMediaFreshness.accept(reply, 2, "2026-10-09", emptySet()).attachments)
    }

    @Test fun aPovPictureCannotKeepTheOldHairOrShadowAfterAnOcChange() {
        val image = asset.copy(kind = "image", visualVersion = 1, dayId = "2026-10-09")
        val reply = LocalMediaReply("我陪你慢慢走。", listOf(image))
        assertTrue(LocalMediaFreshness.accept(reply, 2, "2026-10-09", emptySet(), setOf(id)).attachments.isEmpty())
        assertEquals(reply, LocalMediaFreshness.accept(reply, 1, "2026-10-09", emptySet(), setOf(id)))
        val beforeChoosingOc = image.copy(visualVersion = 0)
        assertEquals(listOf(beforeChoosingOc), LocalMediaFreshness.accept(reply.copy(attachments = listOf(beforeChoosingOc)),
            0, "2026-10-09", emptySet(), setOf(id)).attachments)
    }
}
