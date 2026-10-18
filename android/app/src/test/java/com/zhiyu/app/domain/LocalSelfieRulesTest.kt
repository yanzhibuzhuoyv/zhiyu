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
    @Test fun directUnqualifiedPhotoRequestsMeanCompanionPhotosWithoutConsumingForOffersOrScenery() {
        listOf("发张照片", "给我发照片").forEach { assertTrue(it, LocalSelfieRules.requested(it)) }
        listOf("给你发张照片", "教我怎么拍照片", "怎么拍照片好看", "给我发张风景照", "发张风景照片", "不要给我发照片", "别给我发张照片")
            .forEach { assertFalse(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun recipientAfterThePhotoAndTakingASelfieRequestsAreNotLost() {
        listOf("发张照片给我", "能不能发一张你的自拍给我看看", "拍张照给我看看", "给我拍张自拍", "可以发一张自拍给我吗", "给我发一张你自己的照片")
            .forEach { assertTrue(it, LocalSelfieRules.requested(it)) }
        listOf("我拍张照给你看看", "发张风景照给我", "不要拍张照给我", "你能发照片吗", "教我拍张自拍", "他说发张照片给我", "你说过发张照片给我，现在不用了")
            .forEach { assertFalse(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun askingTheCompanionToTakeAPhotoIsDistinctFromMyOwnPhotoOrAQuotedRequest() {
        listOf("给我拍张自拍", "请给我拍张你的近照", "能不能给我拍一张自拍")
            .forEach { assertTrue(it, LocalSelfieRules.requested(it)) }
        listOf("我拍张自拍", "我想拍张自拍", "这是我拍的自拍", "不要给我拍张自拍", "他说给我拍张自拍",
            "“给我拍张自拍”是我刚才说的", "给我拍张自拍，算了")
            .forEach { assertFalse(it, LocalSelfieRules.requested(it)) }
    }
    @Test fun wholeMessageTakingAndPostRecipientRequestsAreStandaloneButCompoundQuestionsKeepTheirAnswer() {
        listOf("给我拍张自拍", "拍张照给我看看", "发张照片给我")
            .forEach { assertTrue(it, LocalSelfieRules.standaloneRequest(it)) }
        listOf("给我拍张自拍，顺便说说今天怎么样", "拍张照给我看看，晚上吃什么", "发张照片给我。刚才那个问题你怎么看？",
            "我拍张自拍给你", "他说发张照片给我", "不要给我拍张自拍")
            .forEach { assertFalse(it, LocalSelfieRules.standaloneRequest(it)) }
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
