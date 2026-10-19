package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test

class SelfPortraitQuestionTest {
    @Test fun onlyConfirmedOwnershipCanAnswerAnEntireSimpleIdentityQuestion() {
        for (question in listOf("这是谁？", "这是你吗", "你认不认识自己？", "图里的是谁啊？", "照片里是谁")) {
            assertFalse(question, SelfPortraitQuestion.reply(true, question, "gentle").isNullOrBlank())
            assertNull(question, SelfPortraitQuestion.reply(false, question, "gentle"))
        }
    }

    @Test fun compoundQuestionsDescriptionsQuotesAndOtherPeopleRemainWithTheProvider() {
        for (text in listOf("这是谁，她穿了什么？", "这是你吗？帮我看看裙子", "她是谁的朋友？", "我说的是：这是谁？",
            "这是你的猫吗", "这是谁家的房子？", "这是谁画的？", "认不认识这个角色", "你觉得这是谁", "图里有几个人？")) {
            assertNull(text, SelfPortraitQuestion.reply(true, text, "gentle"))
        }
    }
}
