package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalMemoryRulesTest {
    @Test fun longTailExcerptVerifiesTheWholeOriginalAndCannotHideCorruption() {
        val text = "长原文🐈".repeat(20000) + "尾部生日是九月十八日"
        val encoded = LocalMemoryCodec.encode(text)
        val start = text.length - 50
        assertEquals(text.substring(start), LocalMemoryCodec.decodeSpan(encoded.compressed, encoded.utf8Bytes, encoded.sha256, start, text.length))
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decodeSpan(encoded.compressed, encoded.utf8Bytes, "0".repeat(64), start, text.length) }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decodeSpan(encoded.compressed, encoded.utf8Bytes, encoded.sha256, text.length, text.length + 5) }
    }
    @Test fun streamingTailRetainsValidEmojiAndVerifiesTheUnshownOriginal() {
        val text = "原文🐈".repeat(30000) + "生日尾标"
        val encoded = LocalMemoryCodec.encode(text)
        val tail = LocalMemoryCodec.decodeTail(encoded.compressed, encoded.utf8Bytes, encoded.sha256, 1001)
        assertTrue(tail.text.endsWith("生日尾标"))
        assertTrue(tail.text.length <= 1001)
        assertFalse(Character.isLowSurrogate(tail.text.first()))
        assertEquals(text.substring(tail.startOffset), tail.text)
        assertEquals(text.length, tail.originalChars)
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decodeTail(encoded.compressed, encoded.utf8Bytes, "0".repeat(64), 1001) }
    }
    @Test fun independentLongUnicodeTextRoundTripsWithItsOriginalChecksum() {
        val text = "我生日是六月十日。照片里的猫🐈在窗边。\n".repeat(1200)
        val encoded = LocalMemoryCodec.encode(text)
        assertTrue(encoded.compressed.size < encoded.utf8Bytes)
        assertEquals(text, LocalMemoryCodec.decode(encoded.compressed, encoded.utf8Bytes, encoded.sha256))
        assertEquals(LocalMemoryCodec.sha(text.toByteArray(Charsets.UTF_8)), encoded.sha256)
    }
    @Test fun corruptedArchivesAndFalseLengthsNeverReturnInventedOriginals() {
        val encoded = LocalMemoryCodec.encode("完整的独立原文🐈")
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decode(encoded.compressed, encoded.utf8Bytes + 1, encoded.sha256) }
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decode(encoded.compressed, encoded.utf8Bytes, "0".repeat(64)) }
    }
    @Test fun anUnpairedSurrogateCannotBeSilentlyReplacedInAnOriginal() {
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.encode("坏字符\uD800") }
        assertEquals("完整🐈", LocalMemoryCodec.decode(LocalMemoryCodec.encode("完整🐈").compressed,
            "完整🐈".toByteArray(Charsets.UTF_8).size, LocalMemoryCodec.sha("完整🐈".toByteArray(Charsets.UTF_8))))
    }
    @Test fun aLongRecentMessageHasAContinuousAttributedExcerptAndTheLatestUserIsKeptOnce() {
        val long = Message("long", "assistant", "长原文".repeat(30000) + "尾部标记", emptyList(), "earlier")
        val latest = Message("latest", "user", "你今天过得怎么样？", emptyList(), "now")
        val context = LocalMemoryRules.recent(listOf(long, latest), 2000)
        assertEquals(latest, context.last())
        assertEquals(1, context.count { it.id == latest.id })
        assertTrue(context.first().content.contains("尾部标记"))
        assertTrue(context.first().content.contains("UTF16范围"))
        assertTrue(context.sumOf { it.content.length } <= 2000)
        assertEquals(90004, long.content.length)
    }
    @Test fun chineseBigramQueriesDoNotBecomeFtsOperatorsOrSplitSurrogateCharacters() {
        val query = LocalMemoryRules.matchQuery("你记得我的生日吗？\" OR *")
        assertTrue(query.contains("生日"))
        assertFalse(query.contains("\" OR *"))
        val original = "小猫🐈生日".repeat(600)
        val parts = LocalMemoryRules.parts(original)
        assertEquals(original, parts.joinToString("") { it.text })
        assertTrue(parts.none { Character.isLowSurrogate(it.text.first()) || Character.isHighSurrogate(it.text.last()) })
    }
    @Test fun ordinarySharingSkipsOldMemoryWhileSpecificRecallUsesIt() {
        assertFalse(LocalMemoryRules.shouldRetrieve("我刚下班，好困呀"))
        assertTrue(LocalMemoryRules.shouldRetrieve("你知道我的生日吗"))
        assertTrue(LocalMemoryRules.shouldRetrieve("上次答应一起去哪里来着"))
    }
    @Test fun recentContextUsesWholeOriginalsInChronologicalOrderWithinItsBudget() {
        val originals = List(10) { Message("$it", "user", "原文$it".repeat(20), emptyList(), "$it") }
        val context = LocalMemoryRules.recent(originals, 240)
        assertTrue(context.sumOf { it.content.length } <= 240)
        assertEquals(originals.takeLast(context.size), context)
    }
    @Test fun uncertaintyStaysNaturalAndPreservesAuthorsAndIndependentEvidence() {
        val recalls = listOf(LocalRecall("one", "user", "我的生日是六月十日", "2026-10-01", "permanent", 2.0),
            LocalRecall("two", "assistant", "我猜是五月吧", "2026-10-02", "mid", 1.0))
        val system = LocalCompanionPrompt.build(CompanionProfile(style = "steady"), recalls, emptyList())
        assertTrue(system.contains("我没印象了"))
        assertTrue(system.contains("不能把未找到说成用户没讲过"))
        assertTrue(system.contains("\"message_id\":\"one\""))
        assertTrue(system.contains("\"message_id\":\"two\""))
        assertTrue(system.contains("AI以前的猜测不是用户的事实"))
        assertTrue(system.contains("成熟稳重"))
    }
    @Test fun ordinaryShortQueriesKeepTheirExistingTermsAndOrder() {
        assertEquals(listOf("生日","apple_42","猫"),LocalMemoryRules.queryTerms("生日 APPLE_42 猫🐈"))
    }
    @Test fun longInputIncludesTheActualTailQuestionInsteadOfOnlyItsFirstThousandCharacters() {
        val query = "河边天气闲聊。".repeat(1800) + "你记得我的生日吗？"
        assertTrue(query.length in 1001..16000)
        assertTrue(LocalMemoryRules.queryTerms(query).contains("生日"))
    }
    @Test fun denseLongInputKeepsHeadMiddleAndTailAnchorsUnderTheFortyTermLimit() {
        val noise = (0 until 6500).joinToString("") { (0x4e00 + it % 1000).toChar().toString() }
        val query = "start_anchor $noise middle_anchor $noise tail_anchor"
        val terms = LocalMemoryRules.queryTerms(query)
        assertTrue(query.length <= 16000)
        assertTrue(terms.containsAll(listOf("start_anchor","middle_anchor","tail_anchor")))
        assertTrue(terms.size <= 40); assertEquals(terms.size,terms.distinct().size)
    }
    @Test fun longQueryWindowsPreserveChineseBoundaryBigramsAndSupplementaryCharacters() {
        assertTrue(LocalMemoryRules.queryTerms("a".repeat(1023) + "生日").contains("生日"))
        val terms = LocalMemoryRules.queryTerms("𠮷".repeat(7000) + "生日\" OR *")
        assertTrue(terms.contains("生日")); assertTrue(terms.size <= 40)
        assertTrue(terms.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
        assertFalse(LocalMemoryRules.matchQuery("𠮷".repeat(7000) + "生日\" OR *").contains("\" OR *"))
    }
}
