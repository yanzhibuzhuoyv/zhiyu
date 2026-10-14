package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ContactPauseIntentTest {
    @Test fun directExplicitPauseCommandsAreRecognizedAsContactScope() {
        listOf("别再主动给我发消息", "不要再主动给我发消息", "不要主动找我", "别再主动联系我",
            "暂停主动联系", "停止主动联系", "先别主动联系我", "暂时别主动联系我").forEach {
            assertEquals(it, ContactPauseCommand.PAUSE, ContactPauseIntent.parse(it))
        }
    }
    @Test fun directExplicitRestoreCommandsAreRecognized() {
        listOf("可以主动找我了", "你可以主动联系我了", "可以主动给我发消息了", "恢复主动联系",
            "重新开启主动联系", "允许主动联系我", "可以恢复主动联系了").forEach {
            assertEquals(it, ContactPauseCommand.RESUME, ContactPauseIntent.parse(it))
        }
    }
    @Test fun PolitenessAndSentencePunctuationDoNotChangeDirectCommandMeaning() {
        assertEquals(ContactPauseCommand.PAUSE, ContactPauseIntent.parse("  请别再主动给我发消息。 "))
        assertEquals(ContactPauseCommand.PAUSE, ContactPauseIntent.parse("麻烦你暂停主动联系！"))
        assertEquals(ContactPauseCommand.RESUME, ContactPauseIntent.parse("请恢复主动联系。"))
    }
    @Test fun stoppingOneExchangeOrRoleplayDoesNotPauseAllProactiveContact() {
        listOf("算了", "别这样", "停止", "我们别争了", "这个角色扮演停止", "今天不想玩了", "别再撒娇",
            "停止这次刺激", "先不要问了", "别继续这个话题", "我不想听你唱歌").forEach { assertNull(it, ContactPauseIntent.parse(it)) }
    }
    @Test fun OrdinaryRepliesAndMakingUpDoNotImplicitlyResumeAContactPause() {
        listOf("我们和好了", "继续聊天", "你好", "我回来了", "刚刚只是闹别扭", "可以继续了",
            "再说一句", "我今天很开心").forEach { assertNull(it, ContactPauseIntent.parse(it)) }
    }
    @Test fun quotesThirdPartySpeechAndFictionalOcLinesCannotChangeContactPermission() {
        listOf("妈妈说别再主动给我发消息", "朋友让我恢复主动联系", "他说：别再主动给我发消息",
            "OC台词：恢复主动联系", "剧本：你可以主动联系我了", "\"别再主动给我发消息\"",
            "‘可以主动找我了’", "她说“暂停主动联系”", "> 别再主动给我发消息",
            "```\n暂停主动联系\n```", "<user>恢复主动联系</user>").forEach { assertNull(it, ContactPauseIntent.parse(it)) }
    }
    @Test fun questionsNegationConditionsAndInstructionsAboutOtherTextAreNotDirectCommands() {
        listOf("可以主动找我了吗？", "可以主动找我了？", "你为什么别再主动给我发消息？",
            "我没说别再主动给我发消息", "不要停止主动联系", "不是恢复主动联系", "如果我说暂停主动联系你会怎么办",
            "把暂停主动联系这句话翻译成英语", "记住我朋友说的恢复主动联系", "暂停主动联系是什么意思").forEach {
            assertNull(it, ContactPauseIntent.parse(it))
        }
    }
    @Test fun osNotificationsRemindersAndMessageSpecificRefusalsAreSeparateScopes() {
        listOf("把通知权限关掉", "静音通知", "别提醒我吃饭", "取消生日提醒", "别给我发这张照片",
            "不要给我推这个商品", "这次别主动碰我", "别自动生成图片").forEach { assertNull(it, ContactPauseIntent.parse(it)) }
    }
    @Test fun oversizedOrEmbeddedControlTextDoesNotTriggerStateChanges() {
        listOf("x".repeat(16001), "暂停主动联系\u0000", "别再主动给我发消息\n恢复主动联系", "暂\u200B停主动联系").forEach {
            assertNull(ContactPauseIntent.parse(it))
        }
    }
}
