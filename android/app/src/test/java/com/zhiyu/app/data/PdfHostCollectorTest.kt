package com.zhiyu.app.data

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PdfHostCollectorTest {
    private val request = "846ba26a-77ad-4b6e-babb-778e0b15cbaf"
    private fun collector(maxText: Int = PdfHostCollector.MAX_TEXT) = PdfHostCollector(request, 111, 222, maxText)
    private fun text(sequence: Int, text: String, requestId: String = request) =
        PdfHostReply(PdfHostCollector.TEXT, requestId, text = text, sequence = sequence)
    private fun complete(text: String, packets: Int, requestId: String = request,
                         total: Long = text.length.toLong(), sha: String = checksum(text),
                         pid: Int = 333, uid: Int = 444) =
        PdfHostReply(PdfHostCollector.COMPLETE, requestId, sequence = packets, total = total, sha = sha, pid = pid, uid = uid)
    private fun assertRejected(collector: PdfHostCollector) {
        assertTrue(collector.finished)
        assertFalse(collector.ready)
        assertNotNull(collector.failure)
        assertThrows(IllegalStateException::class.java) { collector.content() }
    }

    @Test fun completePreservesEveryCodeUnitEvenWhenTheSurrogatePairCrossesPackets() {
        val original = " \n中文原文\uD83D\uDE00翅膀\n最后一行\t"
        val split = original.indexOf('\uD83D') + 1
        val host = collector()
        host.accept(text(0, original.substring(0, split)))
        assertFalse(host.finished)
        assertThrows(IllegalStateException::class.java) { host.content() }
        host.accept(text(1, original.substring(split)))
        host.accept(complete(original, 2))
        assertTrue(host.ready)
        assertNull(host.failure)
        assertEquals(original, host.content())
        // Late/duplicate completion cannot mutate a successfully verified original.
        host.accept(text(2, "迟到正文"))
        host.accept(complete("错误", 99))
        assertEquals(original, host.content())
    }

    @Test fun missingMiddlePacketIsRejectedAtTheNextSequenceAndCannotBecomeReady() {
        val host = collector()
        host.accept(text(0, "第一段"))
        host.accept(text(2, "第三段"))
        assertRejected(host)
        host.accept(text(1, "第二段"))
        host.accept(complete("第一段第二段第三段", 3))
        assertRejected(host)
    }

    @Test fun missingFinalPacketIsRejectedEvenIfACompleteMarkerArrives() {
        val host = collector()
        host.accept(text(0, "第一段"))
        host.accept(complete("第一段最后段", 2))
        assertRejected(host)
    }

    @Test fun duplicateAndOutOfOrderPacketsAreRejected() {
        val duplicate = collector()
        duplicate.accept(text(0, "原文"))
        duplicate.accept(text(0, "原文"))
        assertRejected(duplicate)
        val reversed = collector()
        reversed.accept(text(1, "第二段先到"))
        assertRejected(reversed)
    }

    @Test fun sameLengthCorruptionCannotPassTheSenderOriginalHash() {
        val host = collector()
        host.accept(text(0, "生日九月十九"))
        host.accept(complete("生日九月十八", 1))
        assertRejected(host)
    }

    @Test fun completionLengthSequenceAndHashAreIndependentlyRequired() {
        val original = "完整原文"
        val invalid = listOf(complete(original, 1, total = original.length + 1L),
            complete(original, 2), complete(original, 1, sha = "0".repeat(64)))
        invalid.forEach { packet ->
            val host = collector()
            host.accept(text(0, original))
            host.accept(packet)
            assertRejected(host)
        }
    }

    @Test fun foreignRequestMessagesAreIgnoredWithoutPoisoningTheCorrectRequest() {
        val original = "这次的原文"
        val host = collector()
        host.accept(text(999, "别人的正文", "other-request"))
        host.accept(PdfHostReply(PdfHostCollector.FAILURE, "other-request"))
        host.accept(complete("别人的正文", 1, requestId = "other-request"))
        assertFalse(host.finished)
        host.accept(text(0, original))
        host.accept(complete(original, 1))
        assertEquals(original, host.content())
    }

    @Test fun remoteFailureAndUnknownMessageAreTerminalAndANewRequestStillWorks() {
        listOf(PdfHostCollector.FAILURE, 999).forEach { kind ->
            val failed = collector()
            failed.accept(text(0, "不能使用的部分正文"))
            failed.accept(PdfHostReply(kind, request))
            failed.accept(complete("不能使用的部分正文", 1))
            assertRejected(failed)
        }
        val next = collector()
        next.accept(text(0, "下次正常原文"))
        next.accept(complete("下次正常原文", 1))
        assertEquals("下次正常原文", next.content())
    }

    @Test fun missingTextOversizedPacketAndTotalBudgetAreRejectedWithoutACompleteMarker() {
        val missing = collector()
        missing.accept(PdfHostReply(PdfHostCollector.TEXT, request, sequence = 0))
        assertRejected(missing)
        val oversized = collector()
        oversized.accept(text(0, "字".repeat(PdfHostCollector.MAX_CHUNK + 1)))
        assertRejected(oversized)
        val exhausted = collector(maxText = 5)
        exhausted.accept(text(0, "12345"))
        exhausted.accept(text(1, "6"))
        assertRejected(exhausted)
    }

    @Test fun hostPidOrUidCannotPretendToBeAnIsolatedDecoder() {
        listOf(complete("原文", 1, pid = 111), complete("原文", 1, uid = 222)).forEach { packet ->
            val host = collector()
            host.accept(text(0, "原文"))
            host.accept(packet)
            assertRejected(host)
        }
    }

    // Independently mirror the documented wire checksum, not a production helper.
    private fun checksum(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        text.forEach { char ->
            digest.update((char.code ushr 8).toByte())
            digest.update(char.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
