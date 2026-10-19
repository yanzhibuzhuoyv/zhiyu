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
    @Test fun preservingAMixedReplyCannotClaimAnUnavailablePhotoWasSent() {
        val pending = "这会儿照片还没准备好，等我弄好了再给你看。"
        val mixed = LocalMediaPlan("", emptyList(), "", selfieRequested = true,
            unavailableMessage = pending, preserveGeneratedText = true)
        listOf("照片已经发给你了。今晚先休息。", "已经发啦。今晚先休息。", "这张自拍发过去了。今晚先休息。")
            .forEach { raw ->
                val reply = mixed.parse(raw)
                assertEquals(pending, reply.content)
                assertTrue(reply.attachments.isEmpty())
            }
        listOf("你说“已经发啦”，我没看到。今晚先休息。", "照片还没发，等我弄好再给你。今晚先休息。",
            "照片等我准备好再发给你。今晚先休息。", "我没有说已经发啦。今晚先休息。")
            .forEach { truthful -> assertEquals(truthful, mixed.parse(truthful).content) }
    }
    @Test fun aReadySelfieCaptionRequiresASelectedImageAndDoesNotReplaceAMixedAnswer() {
        val image = asset.copy(kind = "image")
        val ready = LocalMediaPlan("", listOf(image), "", selfieRequested = true,
            requiredAttachmentId = id, readyCaption = "喏，给你看这张。")
        val reply = ready.parse("还没弄好，我不能发照片。")
        assertEquals(ready.readyCaption, reply.content); assertEquals(listOf(image), reply.attachments)
        val missing = ready.copy(candidates = emptyList(), unavailableMessage = "这会儿还没准备好。")
            .parse("还没弄好。")
        assertEquals("这会儿还没准备好。", missing.content); assertTrue(missing.attachments.isEmpty())
        val compound = "考试没发挥好先别急。这张给你看看。"
        assertEquals(compound, ready.copy(preserveGeneratedText = true).parse(compound).content)
        assertEquals(compound, ready.copy(selfieRequested = false).parse(compound).content)
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

    @Test fun anAcceptedLifePhotoUsesItsRequiredActualImageWithoutDependingOnModelMarkers() {
        val image = asset.copy(kind = "image")
        val ready = LocalMediaPlan("", listOf(image), "", requiredAttachmentId = id,
            readyCaption = "给你看看这张。", photoRequested = true, standaloneMediaRequest = true)
        val reply = ready.parse("行，我翻翻看。")
        assertEquals("给你看看这张。", reply.content); assertEquals(listOf(image), reply.attachments)
        assertFalse(ready.selfieRequested)
    }

    @Test fun aMissingLifePhotoRemovesOnlyUnsupportedPhotoClausesAndKeepsTheOtherAnswer() {
        val pending = "这条还没有附上照片，我现在没有可发的合适图片。"
        val plan = LocalMediaPlan("", emptyList(), "", unavailableMessage = pending,
            preserveGeneratedText = true, photoRequested = true)
        val reply = plan.parse("考试没发挥好先别急。这张窗边橘猫在毯子上。今晚先休息。")
        assertEquals("考试没发挥好先别急。今晚先休息。\n$pending", reply.content)
        assertTrue(reply.attachments.isEmpty())
        assertEquals(pending, plan.copy(preserveGeneratedText = false).parse("这张窗边橘猫很乖。").content)
    }

    @Test fun anUnavailableLifePhotoKeepsTheOtherAnswerAboutARealWorksheet() {
        val pending = "这条还没有附上照片，我现在没有可发的合适图片。"
        val plan = LocalMediaPlan("", emptyList(), "", unavailableMessage = pending,
            preserveGeneratedText = true, photoRequested = true)
        val reply = plan.parse("这张试卷先做第二题。作业慢慢来。这张窗边橘猫在毯子上。")
        assertEquals("这张试卷先做第二题。作业慢慢来。\n$pending", reply.content)
        assertTrue(reply.attachments.isEmpty())
    }

    @Test fun aMissingLifePhotoAlsoRemovesUnsupportedCaptionsStartingWithInsideThePicture() {
        val pending = "这条还没有附上照片，我现在没有可发的合适图片。"
        val plan = LocalMediaPlan("", emptyList(), "", unavailableMessage = pending,
            preserveGeneratedText = true, photoRequested = true)
        listOf("图中", "图片中").forEach { prefix ->
            val reply = plan.parse("考试没发挥好先别急。${prefix}那只橘猫卧在毯子上。今晚先休息。")
            assertEquals(prefix, "考试没发挥好先别急。今晚先休息。\n$pending", reply.content)
            assertTrue(reply.attachments.isEmpty())
        }
    }
}
