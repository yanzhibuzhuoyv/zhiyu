package com.zhiyu.app.data

import com.zhiyu.app.domain.ApiFailure
import org.junit.Assert.*
import org.junit.Test

class LocalDailyShareCaptionTest {
    @Test fun captionCannotClaimItConsultedInternalRecords() {
        listOf("我查了下记录，今天想与你分享一点小事。", "我查了一下手头的记录，再把今天告诉你。").forEach { raw ->
            val failure = assertThrows(ApiFailure::class.java) { LocalDailyShareCaption.clean(raw) }
            assertEquals("daily_share_response_invalid", failure.errorCode)
        }
    }

    @Test fun naturalWritingAndTheUsersWordRecordsRemainUnchanged() {
        listOf("我想记下这点小事，晚一点和你说。", "你说想记录日常，我也想认真听。",
            "这是我今天翻到的读书记录，想分享给你。").forEach { raw ->
            assertEquals(raw, LocalDailyShareCaption.clean(raw))
        }
    }

    @Test fun stripsActionProtocolsButKeepsNaturalCaptionInsteadOfCreatingAnAction() {
        val raw = "今天也想把小小的开心分给你。\n[[ZHIYU_MEDIA_fake:other]]\n" +
            "[[ZHIYU_CALENDAR_fake:{\"kind\":\"diary\",\"title\":\"不执行\"}]]"
        assertEquals("今天也想把小小的开心分给你。", LocalDailyShareCaption.clean(raw))
    }
}
