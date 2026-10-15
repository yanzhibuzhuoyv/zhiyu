package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class CallSpeechFilterTest {
    @Test fun everyPossibleMarkerSplitHidesCalendarAndMediaWithoutChangingVisiblePrefix() {
        listOf("[[ZHIYU_CALENDAR_nonce:{\"description\":\"秘密日记\"}]]", "[[ZHIYU_MEDIA_nonce:asset]]").forEach { marker ->
            for (split in 0..marker.length) {
                val filter = CallSpeechFilter()
                val visible = filter.append("我在听。\n" + marker.take(split)) +
                    filter.append(marker.drop(split) + "\n不要朗读这个尾部") + filter.finish()
                assertEquals("split=$split", "我在听。\n", visible)
            }
        }
    }
    @Test fun firstSentenceIsAvailableBeforeAResponseFinishesAndPlainBracketsArePreserved() {
        val filter = CallSpeechFilter()
        assertEquals("今天很想你。", filter.append("今天很想你。"))
        assertEquals("[苹果]", filter.append("[苹果]"))
        assertEquals("", filter.append("[[ZHI"))
        assertEquals("[[ZHIX日常", filter.append("X日常"))
        assertEquals("", filter.finish())
    }
    @Test fun splitUnicodeIsHeldUntilCompleteAndMalformedTrailingSurrogateIsRejected() {
        val filter = CallSpeechFilter()
        assertEquals("", filter.append("\uD83D"))
        assertEquals("\uD83D\uDE42你好", filter.append("\uDE42你好"))
        val bad = CallSpeechFilter()
        bad.append("\uD83D")
        assertThrows(IllegalArgumentException::class.java) { bad.finish() }
    }
    @Test fun uncertainTruncatedProtocolIsNotSpokenButLiteralBracketIs() {
        val filter = CallSpeechFilter()
        assertEquals("你好", filter.append("你好[[ZHIYU_"))
        assertEquals("", filter.finish())
        val literal = CallSpeechFilter()
        assertEquals("日常", literal.append("日常["))
        assertEquals("[", literal.finish())
    }
}
