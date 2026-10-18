package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class SelfieReadyCaptionTest {
    private val id = "a".repeat(32)
    private val image = MediaAttachment(id, "image", MediaRules.path(id), "image/png", "b".repeat(64), "2026-10-18", 1, "synthetic")
    private val plan = LocalMediaPlan("", listOf(image), "", selfieRequested = true, requiredAttachmentId = id)

    @Test fun readyCaptionsStayShortAndRespectTheSelectedPresetWithoutClaimingARealCamera() {
        val playful = SelfieReadyCaption.content(plan, CompanionProfile(style = "playful"))
        val distant = SelfieReadyCaption.content(plan, CompanionProfile(style = "distant"))
        assertNotEquals(playful, distant); assertTrue(checkNotNull(distant).length < checkNotNull(playful).length)
        for (style in listOf("gentle", "playful", "steady", "anxious", "distant", "warm_v1", "warm_v2")) {
            val caption = checkNotNull(SelfieReadyCaption.content(plan, CompanionProfile(style = style)))
            assertTrue(caption.length in 1..24)
            listOf("没弄好", "不能", "催", "刚拍", "API", "Key", "系统").forEach { assertFalse(caption.contains(it)) }
        }
    }

    @Test fun aCaptionIsUnavailableForMissingDuplicateInvalidOrNonSelfieSelectionsAndMixedReplies() {
        listOf(plan.copy(candidates = emptyList()), plan.copy(candidates = listOf(image, image)),
            plan.copy(requiredAttachmentId = "c".repeat(32)), plan.copy(candidates = listOf(image.copy(sha256 = "invalid"))),
            plan.copy(candidates = listOf(image.copy(kind = "sticker"))), plan.copy(selfieRequested = false),
            plan.copy(preserveGeneratedText = true)).forEach { assertNull(SelfieReadyCaption.content(it, null)) }
    }
}
