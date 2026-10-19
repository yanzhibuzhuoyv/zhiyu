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

    private val now = java.time.Instant.parse("2026-10-19T05:00:00Z")
    private fun line(role: String, text: String, age: Long = 0, attachments: List<MediaAttachment> = emptyList()) =
        Message("a".repeat(32), role, text, emptyList(), now.minusSeconds(age).toString(), attachments)

    @Test fun aLifePhotoOfferCarriesItsSubjectButNeverAuthorizesPaidSelfies() {
        val offer = listOf(line("assistant", "还有几张猫咪照片，要看吗？"))
        listOf("都看看", "看看", "好哦好哦", "还没挑好吗", "只要你发的就行，发一张看看嘛", "诶没看到啊")
            .forEach { text ->
                val request = checkNotNull(LocalPhotoDeliveryRules.resolve(text, offer, now))
                assertFalse(text, request.selfie); assertFalse(text, request.explicit)
                assertTrue(text, request.standalone); assertEquals("cat", request.subject)
            }
        val generic = checkNotNull(LocalPhotoDeliveryRules.resolve("发张照片", offer, now))
        assertFalse(generic.selfie); assertEquals("cat", generic.subject)
    }

    @Test fun ordinaryPolitenessExpiredOffersNewTopicsCancellationAndActualDeliveryEndTheOffer() {
        val offer = line("assistant", "有张猫咪照片，给你看吗？")
        val image = MediaAttachment("b".repeat(32), "image", "unused", "image/png", "c".repeat(64), null, 0, "test")
        listOf(emptyList(), listOf(offer.copy(createdAt = now.minusSeconds(901).toString())),
            listOf(offer, line("user", "晚饭吃什么？"), line("assistant", "煮面吧。")),
            listOf(offer, line("user", "不用发了")),
            listOf(offer, line("assistant", "给你。", attachments = listOf(image))),
            listOf(line("assistant", "还有几张作业，要看哪样？")))
            .forEach { history -> assertNull(LocalPhotoDeliveryRules.resolve("好哦", history, now)) }
        listOf("不用照片", "不要发了", "算了", "他说“都看看”", "教我怎么拍猫咪照片", "我给你发猫咪照片")
            .forEach { assertNull(it, LocalPhotoDeliveryRules.resolve(it, listOf(offer), now)) }
    }

    @Test fun explicitSelfiesKeepTheirExistingPermissionWhileAnAcknowledgmentOnlyUsesStock() {
        val explicit = checkNotNull(LocalPhotoDeliveryRules.resolve("给我拍张自拍", emptyList(), now))
        assertTrue(explicit.selfie); assertTrue(explicit.explicit); assertTrue(explicit.standalone)
        val accepted = checkNotNull(LocalPhotoDeliveryRules.resolve("好哦", listOf(line("user", "给我拍张自拍"),
            line("assistant", "我挑张自拍给你看。")), now))
        assertTrue(accepted.selfie); assertFalse(accepted.explicit)
        assertTrue(checkNotNull(LocalPhotoDeliveryRules.resolve("发张照片", emptyList(), now)).selfie)
        val scenery = checkNotNull(LocalPhotoDeliveryRules.resolve("给我发张风景照", emptyList(), now))
        assertFalse(scenery.selfie); assertEquals("landscape", scenery.subject)
    }

    @Test fun mixedRequestsKeepTheirOtherAnswerAndARecordedCurrentUserDoesNotBecomeItsOwnOffer() {
        val offer = line("assistant", "还有几张照片，要看哪样？")
        val text = "都看看，晚饭吃什么？"
        val request = checkNotNull(LocalPhotoDeliveryRules.resolve(text, listOf(offer, line("user", text)), now))
        assertFalse(request.standalone)
        assertNull(LocalPhotoDeliveryRules.resolve("好哦", listOf(line("user", "好哦")), now))
        assertNotNull(LocalPhotoDeliveryRules.resolve("都看看", listOf(line("assistant", "还有几张要看哪样？")), now))
    }

    @Test fun aNegativeOrUnavailablePhotoStatementDoesNotTurnPolitenessIntoAnOffer() {
        listOf("我没有照片给你看。", "我不能发照片给你看。", "照片还没准备好给你看。")
            .forEach { statement ->
                assertNull(statement, LocalPhotoDeliveryRules.resolve("好哦", listOf(line("assistant", statement)), now))
            }
    }

    @Test fun questionsAboutAnExistingPictureAreNotRequestsForNewStock() {
        listOf("看看这张猫咪照片里写了什么", "看看这张风景照片有什么问题", "给我分析这张猫咪照片")
            .forEach { question -> assertNull(question, LocalPhotoDeliveryRules.resolve(question, emptyList(), now)) }
        assertNotNull(LocalPhotoDeliveryRules.resolve("给我发张猫咪照片", emptyList(), now))
    }
}
