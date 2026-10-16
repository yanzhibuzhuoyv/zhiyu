package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalSelfieRulesTest {
    @Test fun directRequestsAreRecognizedWithoutModelMediaMarkers() {
        listOf("给我发张自拍", "想看看你的照片", "来张自拍吧", "你的自拍呢", "发一张你的近照", "send me a selfie")
            .forEach { assertTrue(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun usersOwnPhotosNegationsAndUnrelatedChatDoNotForceSelfies() {
        listOf("这是我的自拍", "我刚拍了自拍", "不要发自拍", "别给我发你的照片", "不用自拍，聊聊天", "今天吃什么")
            .forEach { assertFalse(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun usersPhotoOffersAndSelfieTechniqueQuestionsNeverConsumeCompanionInventory() {
        listOf("给你看张自拍", "教我怎么自拍", "我想拍张自拍", "给你发一张自拍", "帮我看看这张自拍", "如何自拍更好看")
            .forEach { assertFalse(it, LocalSelfieRules.requested(it)) }
        listOf("给我发张自拍", "想看你的自拍").forEach { assertTrue(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun threeLanesAndConsumptionGenerationsHaveDistinctValidatedMarkers() {
        val scope = "a".repeat(64)
        val first = LocalSelfieTicket(scope, 0, 0)
        assertEquals(2, LocalSelfieRules.tags(first).size)
        assertNotEquals(LocalSelfieRules.tags(first), LocalSelfieRules.tags(first.copy(lane = 1)))
        assertNotEquals(LocalSelfieRules.tags(first), LocalSelfieRules.tags(first.copy(generation = 1)))
        listOf(first.copy(lane = -1), first.copy(lane = 3), first.copy(generation = -1), first.copy(scope = "bad"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { LocalSelfieRules.check(it) } }
    }
}
