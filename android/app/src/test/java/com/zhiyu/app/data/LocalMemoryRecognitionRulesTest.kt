package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class LocalMemoryRecognitionRulesTest {
    private fun source(text: String, role: String = "user") = LocalMemoryOriginal("owner", "chat", "message", role,
        "2026-10-09T00:00:00Z", sha(text), 0, text)
    private fun claim(quote: String, kind: String = "profile", subject: String = "user") = LocalMemoryRawClaim(
        quote, kind, subject, "affirm", "birthday", .95, false, false, null, null, null)

    @Test fun emptyPhotoCaptionIsAValidUntouchedOriginalButCannotSupplyATextFact() {
        listOf("", " \n\t\u3000 ").forEach { caption ->
            val original = source(caption)
            assertEquals(caption, original.text); assertEquals(sha(caption), original.sha256)
            assertThrows(IllegalArgumentException::class.java) {
                LocalMemoryRecognitionRules.ground(LocalMemoryWindow(original, 0, caption.length), claim("我的生日是九月十八"))
            }
        }
    }

    @Test fun birthdayQuoteRetainsExactUtf16SpanAndSourceIdentity() {
        val original = source("😀\n我的生日是九月十八，别弄错了。\n")
        val quote = "我的生日是九月十八"
        val annotation = LocalMemoryRecognitionRules.ground(LocalMemoryWindow(original, 0, original.text.length), claim(quote))!!
        assertEquals(3, annotation.start)
        assertEquals(quote, original.text.substring(annotation.start, annotation.end))
        assertEquals(sha(quote), annotation.spanSha)
        assertEquals(original.sha256, annotation.sourceSha)
        assertEquals("user:birthday", annotation.topicKey)
        assertEquals(original.messageId, annotation.messageId)
        assertEquals(original.createdAt, annotation.createdAt)
    }

    @Test fun repeatedQuotesRequireExplicitExactPositionsAndWrongPositionIsRejected() {
        val original = source("我想吃苹果。过几天我想吃苹果。")
        val window = LocalMemoryWindow(original, 0, original.text.length)
        val raw = claim("我想吃苹果", "wish").copy(topicKey = "wish:apple")
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionRules.ground(window, raw) }
        val start = original.text.lastIndexOf(raw.quote)
        assertEquals(start, LocalMemoryRecognitionRules.ground(window, raw.copy(start = start, end = start + raw.quote.length))!!.start)
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionRules.ground(window, raw.copy(start = 1, end = 6)) }
    }

    @Test fun uncertainThirdPersonAndAssistantEchoCannotCreateUserProfile() {
        val user = source("小明的生日是九月十八，我只是举个例子。")
        val window = LocalMemoryWindow(user, 0, user.text.length)
        assertNull(LocalMemoryRecognitionRules.ground(window, claim(user.text, subject = "other")))
        assertNull(LocalMemoryRecognitionRules.ground(window, claim(user.text).copy(polarity = "uncertain")))
        assertNull(LocalMemoryRecognitionRules.ground(window, claim(user.text).copy(confidence = .84)))
        val ai = source("你的生日是九月十八", "assistant")
        assertNull(LocalMemoryRecognitionRules.ground(LocalMemoryWindow(ai, 0, ai.text.length), claim(ai.text, subject = "assistant")))
    }

    @Test fun futureUndoneOrNotCancelledPromisesCannotBeDeclaredResolved() {
        listOf("我明天准备陪你吃火锅", "我还没完成陪你去看海的承诺", "我的承诺没有取消").forEach { text ->
            val original = source(text)
            val raw = claim(text, "promise").copy(topicKey = "promise:trip", promiseState = if (text.contains("取消")) "cancelled" else "fulfilled")
            assertNull(LocalMemoryRecognitionRules.ground(LocalMemoryWindow(original, 0, text.length), raw))
        }
    }

    @Test fun assistantPromiseCanBePendingButCannotSetExplicitUserKeep() {
        val original = source("我会一直陪你，明天带你去吃火锅。", "assistant")
        val raw = claim(original.text, "promise", "assistant").copy(topicKey = "promise:hotpot", explicitKeep = true)
        val annotation = LocalMemoryRecognitionRules.ground(LocalMemoryWindow(original, 0, original.text.length), raw)!!
        assertEquals("pending", annotation.promiseState)
        assertFalse(annotation.explicitKeep)
    }

    @Test fun unstatedCalendarAndModelInventedEventDatesAreNotFacts() {
        val original = source("我的生日是九月十八")
        val window = LocalMemoryWindow(original, 0, original.text.length)
        assertNull(LocalMemoryRecognitionRules.ground(window, claim(original.text).copy(dateBasis = "lunar"))!!.dateBasis)
        assertThrows(IllegalArgumentException::class.java) {
            LocalMemoryRecognitionRules.ground(window, claim(original.text).copy(eventDate = "2026-09-18"))
        }
    }

    @Test fun fullLongOriginalWindowsHaveNoGapsAndNeverSplitASurrogatePair() {
        val original = source("前".repeat(15999) + "😀" + "尾".repeat(18000))
        val first = LocalMemoryRecognitionRules.window(original, 0)
        assertEquals(15999, first.end)
        val second = LocalMemoryRecognitionRules.window(original, first.end)
        val third = LocalMemoryRecognitionRules.window(original, second.end)
        assertEquals(original.text, first.text + second.text + third.text)
        val quote = "尾".repeat(4)
        val raw = claim(quote, "wish").copy(topicKey = "wish:tail", start = third.text.length - 4, end = third.text.length)
        val tail = LocalMemoryRecognitionRules.ground(third, raw)!!
        assertEquals(original.text.length - 4, tail.start)
        assertEquals(sha(quote), tail.spanSha)
    }

    @Test fun invalidNonfiniteConfidenceAndUnsupportedFieldsAreRejected() {
        val original = source("我的生日是九月十八")
        val window = LocalMemoryWindow(original, 0, original.text.length)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.1).forEach { confidence ->
            assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionRules.ground(window, claim(original.text).copy(confidence = confidence)) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryRecognitionRules.ground(window, claim(original.text).copy(kind = "summary")) }
    }

    @Test fun aClaimCannotCutThroughAnOriginalEmojiAndChangeItsUtf8Hash() {
        val original = source("我的标记😀")
        val window = LocalMemoryWindow(original, 0, original.text.length)
        assertThrows(IllegalArgumentException::class.java) {
            LocalMemoryRecognitionRules.ground(window, claim("\uD83D").copy(start = 4, end = 5))
        }
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
