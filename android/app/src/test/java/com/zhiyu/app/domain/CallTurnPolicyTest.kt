package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class CallTurnPolicyTest {
    @Test fun briefContactAndAcknowledgementDoNotScanTheLongTermArchive() {
        listOf("你好", "嗯。", "好啊！", "听得见吗？", "晚安", "喂", "继续说").forEach {
            assertTrue(it, CallTurnPolicy.recentOnly(it))
        }
    }
    @Test fun memoriesFactsAndUnknownUtterancesAlwaysKeepTheNormalRetrievalPath() {
        listOf("你还记得吗", "我的生日是哪天", "上次你说好的呢", "我想吃苹果", "你好，你记得上次那个人吗", "嗯，我生日是六月二日").forEach {
            assertFalse(it, CallTurnPolicy.recentOnly(it))
        }
    }
    @Test fun endpointWaitIsLessThanHalfASecondWithoutUsingAnInstantSpeechCutoff() {
        assertTrue(CallTurnPolicy.endpointMillis in 400..499)
    }
}
