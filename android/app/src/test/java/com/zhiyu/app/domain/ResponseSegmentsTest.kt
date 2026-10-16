package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class ResponseSegmentsTest {
    @Test fun independentSentencesBecomeSeparateMessagesWithoutLengthPadding() {
        val text = "好啊。等我一下！你先挑地方？"
        assertEquals(listOf("好啊。", "等我一下！", "你先挑地方？"), ResponseSegments.split(text).map { it.text })
        assertOriginal(text)
    }

    @Test fun explicitParagraphsKeepWhitespaceAndEveryOriginalOffset() {
        val text = "嗯，听到了。\n\n  我还想听你讲🍎\r\n晚安"
        val parts = ResponseSegments.split(text)
        assertTrue(parts.size >= 3)
        assertOriginal(text)
    }

    @Test fun linksDecimalsAndEmojiSequencesStayWhole() {
        val text = "去 https://example.com/a?x=3.14!y=2 看看。约在 3.14 公里外？👩🏽‍💻好呀！"
        val parts = ResponseSegments.split(text)
        assertEquals(3, parts.size)
        assertTrue(parts.first().text.contains("https://example.com/a?x=3.14!y=2"))
        assertTrue(parts[1].text.contains("3.14"))
        assertTrue(parts.last().text.startsWith("👩🏽‍💻"))
        assertOriginal(text)
    }

    @Test fun closingQuotationMarksStayWithTheirSentence() {
        val text = "她说：“今天真好！”我也是。"
        assertEquals(listOf("她说：“今天真好！”", "我也是。"), ResponseSegments.split(text).map { it.text })
        assertOriginal(text)
    }

    @Test fun codeBlocksAndInlineCodeCannotCreateFalseMessages() {
        val text = "我看了。\n```kotlin\nval x = \"啊！\"\nprintln(x)\n```\n这里的 `a!b?c` 不拆。"
        val parts = ResponseSegments.split(text)
        assertTrue(parts.any { it.text.contains("```kotlin\nval x = \"啊！\"\nprintln(x)\n```") })
        assertTrue(parts.any { it.text.contains("`a!b?c`") })
        assertOriginal(text)
    }

    @Test fun punctuationFreeLongThoughtIsNeverChoppedAtArbitraryCharacterCount() {
        val text = "我今天想告诉你一件事情".repeat(80)
        assertEquals(listOf(text), ResponseSegments.split(text).map { it.text })
    }

    @Test fun aLongOriginalHasBoundedPresentationWithoutDiscardingAnyRemainder() {
        val text = "嗯。".repeat(20_000)
        val parts = ResponseSegments.split(text)
        assertTrue(parts.size in 2..16)
        assertOriginal(text)
        assertTrue(parts.none { it.text.isBlank() })
    }

    @Test fun emptyAndOnlyWhitespaceHaveNoManufacturedWords() {
        assertTrue(ResponseSegments.split("").isEmpty())
        assertOriginal("  \n\r\n")
    }

    private fun assertOriginal(text: String) {
        val parts = ResponseSegments.split(text)
        assertEquals(text, parts.joinToString("") { it.text })
        var next = 0
        parts.forEach { part ->
            assertEquals(next, part.start)
            assertTrue(part.end > part.start)
            assertEquals(text.substring(part.start, part.end), part.text)
            assertFalse(part.text.first().isLowSurrogate())
            assertFalse(part.text.last().isHighSurrogate())
            next = part.end
        }
        assertEquals(text.length, next)
    }
}
