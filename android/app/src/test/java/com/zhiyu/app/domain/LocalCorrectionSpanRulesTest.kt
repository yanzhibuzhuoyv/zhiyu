package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalCorrectionSpanRulesTest {
    @Test fun narrowDenialRetainsTheNextSentenceInTheSameShortOriginal() {
        val text = "不是九月十八日。正确的生日是四月二十四号。"
        val result = LocalCorrectionSpanRules.select(text,0,"不是九月十八日".length)
        assertEquals(text,result.text)
        assertEquals(0,result.start); assertEquals(text.length,result.end)
        assertFalse(result.requiresSupplement)
    }
    @Test fun anAlreadyRetrievedWiderContinuousSpanIsPreserved() {
        val text = "说明。".repeat(5000) + "不是九月十八日。正确的生日是四月二十四号。" + "尾声。".repeat(1000)
        val start = text.indexOf("不是九月十八日")
        val end = text.indexOf("尾声。")
        val supplied = LocalCorrectionSpan(start,end,text.substring(start,end),false)
        val result = LocalCorrectionSpanRules.select(text,start,start + "不是九月十八日".length,supplied)
        assertEquals(supplied.text,result.text)
        assertEquals(supplied.start,result.start); assertEquals(supplied.end,result.end)
        assertTrue("An incomplete long source must still request precision supplementation",result.requiresSupplement)
    }
    @Test fun aNarrowExistingSpanDoesNotHideTheRestOfAShortCorrection() {
        val text = "不是九月十八日。正确的生日是四月二十四号。"
        val end = "不是九月十八日".length
        val result = LocalCorrectionSpanRules.select(text,0,end,LocalCorrectionSpan(0,end,text.substring(0,end),false))
        assertEquals(text,result.text)
        assertFalse(result.requiresSupplement)
    }
    @Test fun longSourceContextIsBoundedAndKeepsAdjacentCorrectedValue() {
        val text = "开场🙂".repeat(4000) + "不是九月十八日。正确的生日是四月二十四号。" + "结尾🙂".repeat(4000)
        val start = text.indexOf("不是九月十八日")
        val result = LocalCorrectionSpanRules.select(text,start,start + "不是九月十八日".length,maximum=256)
        assertTrue(result.text.contains("四月二十四号"))
        assertTrue(result.requiresSupplement)
        exact(text,result,256)
    }
    @Test fun aPrecisionTailInsideAnOversizedAnnotationRemainsARealContinuousExcerpt() {
        val text = "说明。".repeat(4500) + "不是九月十八日。正确的生日是四月二十四号。"
        val start = text.length - 80
        val supplied = LocalCorrectionSpan(start,text.length,text.substring(start),false)
        val result = LocalCorrectionSpanRules.select(text,0,text.length,supplied)
        assertEquals(supplied.text,result.text)
        assertTrue(result.requiresSupplement)
        exact(text,result,12000)
    }
    @Test fun boundedContextNeverSplitsSupplementaryCharacters() {
        val text = "🙂".repeat(200) + "更正：五月十号。" + "𠮷".repeat(200)
        val start = text.indexOf("更正")
        val result = LocalCorrectionSpanRules.select(text,start,start + "更正：五月十号。".length,maximum=65)
        exact(text,result,65)
        assertTrue(result.requiresSupplement)
        assertTrue(result.text.contains("五月十号"))
    }
    @Test fun invalidOrFabricatedRangesCannotBecomeOriginalEvidence() {
        val text = "🙂不是九月十八日。"
        assertThrows(IllegalArgumentException::class.java) { LocalCorrectionSpanRules.select(text,1,text.length) }
        assertThrows(IllegalArgumentException::class.java) { LocalCorrectionSpanRules.select(text,0,text.length + 1) }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCorrectionSpanRules.select(text,2,text.length,LocalCorrectionSpan(2,text.length,"虚构的日期",false))
        }
        assertThrows(IllegalArgumentException::class.java) { LocalCorrectionSpanRules.select(text,2,text.length,maximum=12001) }
    }
    private fun exact(source:String,span:LocalCorrectionSpan,maximum:Int) {
        assertEquals(source.substring(span.start,span.end),span.text)
        assertTrue(span.text.isNotEmpty() && span.text.length <= maximum)
        assertFalse(Character.isLowSurrogate(span.text.first()))
        assertFalse(Character.isHighSurrogate(span.text.last()))
    }
}
