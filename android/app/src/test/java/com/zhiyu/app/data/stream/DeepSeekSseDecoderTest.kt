package com.zhiyu.app.data.stream

import com.zhiyu.app.domain.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSseDecoderTest {
    private fun event(content: String, index: Int = 0) =
        "data: {\"choices\":[{\"index\":$index,\"delta\":{\"content\":$content},\"finish_reason\":null}]}\n\n"
    private val stop = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
    private val done = "data: [DONE]\n\n"
    private suspend fun fails(body: suspend () -> Unit): ApiFailure {
        try { body(); fail("Incomplete/untrusted response must fail") }
        catch (failure: ApiFailure) { return failure }
        throw AssertionError("Missing provider failure")
    }

    @Test fun chineseEmojiByteBoundariesEmitBeforeCompletionAndPreserveTheOriginal() = runTest {
        val heard = mutableListOf<String>(); val decoder = DeepSeekSseDecoder { heard += it }
        event("\"你好😀，今天想你了。\"").toByteArray().forEach { decoder.accept(byteArrayOf(it)) }
        assertEquals("你好😀，今天想你了。", heard.joinToString(""))
        decoder.accept((stop + done).toByteArray())
        assertEquals(heard.joinToString(""), decoder.finish())
    }

    @Test fun commentsCrLfAndMultilineDataFollowSseFramingRatherThanChunkBoundaries() = runTest {
        val heard = mutableListOf<String>(); val decoder = DeepSeekSseDecoder { heard += it }
        val wire = ": keepalive\r\nid: 1\r\nevent: message\r\ndata: {\"choices\":[\r\ndata: {\"index\":0,\"delta\":{\"content\":\"在呢。\"},\"finish_reason\":null}]}\r\n\r\n"
        wire.toByteArray().asList().chunked(3).forEach { decoder.accept(it.toByteArray()) }
        decoder.accept((stop + done).toByteArray())
        assertEquals("在呢。", decoder.finish()); assertEquals(listOf("在呢。"), heard)
    }

    @Test fun reasoningAndOtherChoicesNeverBecomeSpeech() = runTest {
        val heard = mutableListOf<String>(); val decoder = DeepSeekSseDecoder { heard += it }
        decoder.accept(("data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"秘密推理\"},\"finish_reason\":null}]}\n\n" +
            event("\"其他候选\"", 1) + event("\"抱抱。\"") + stop + done).toByteArray())
        assertEquals("抱抱。", decoder.finish()); assertEquals(listOf("抱抱。"), heard)
    }

    @Test fun splitThinkMarkersAreBlockedBeforeTheirContentsCanBeSpoken() = runTest {
        val heard = mutableListOf<String>(); val decoder = DeepSeekSseDecoder { heard += it }
        decoder.accept(event("\"<thi\"").toByteArray())
        fails { decoder.accept(event("\"nk>秘密推理\"").toByteArray()) }
        assertTrue(heard.isEmpty())
    }

    @Test fun missingDoneOrMissingStopCannotReturnASuccess() = runTest {
        val noDone = DeepSeekSseDecoder {}; noDone.accept((event("\"你好\"") + stop).toByteArray())
        fails { noDone.finish() }
        val noStop = DeepSeekSseDecoder {}; noStop.accept(event("\"你好\"").toByteArray())
        fails { noStop.accept(done.toByteArray()); noStop.finish() }
    }

    @Test fun lengthErrorsAndProviderErrorsCannotSaveAnIncompleteAnswer() = runTest {
        val decoder = DeepSeekSseDecoder {}; decoder.accept(event("\"说了一半\"").toByteArray())
        val limit = fails { decoder.accept("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}\n\n".toByteArray()) }
        assertEquals("provider_output_limit", limit.errorCode)
        val upstream = DeepSeekSseDecoder {}
        fails { upstream.accept("event: error\ndata: {\"error\":{\"message\":\"private-upstream-error\"}}\n\n".toByteArray()) }
    }

    @Test fun invalidUtf8DuplicateFieldsAndContentAfterStopAreRejected() = runTest {
        val invalid = DeepSeekSseDecoder {}
        fails { invalid.accept("data: ".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\n\n".toByteArray()) }
        val duplicate = DeepSeekSseDecoder {}
        fails { duplicate.accept("data: {\"choices\":[],\"choices\":[]}\n\n".toByteArray()) }
        val late = DeepSeekSseDecoder {}; late.accept((event("\"你好\"") + stop).toByteArray())
        fails { late.accept(event("\"晚到正文\"").toByteArray()) }
    }

    @Test fun individualEventsAndTheWholeResponseHaveSeparateMemoryLimits() = runTest {
        val oneEvent = DeepSeekSseDecoder {}
        fails { oneEvent.accept(("data: " + "x".repeat(65537)).toByteArray()) }
        val whole = DeepSeekSseDecoder {}
        fails { repeat(257) { whole.accept((":" + " ".repeat(8191) + "\n").toByteArray()) } }
    }

    @Test fun blankOrReasoningOnlyAnswerIsNotACompletedPublicReply() = runTest {
        val decoder = DeepSeekSseDecoder {}; decoder.accept((stop + done).toByteArray())
        fails { decoder.finish() }
    }

    @Test fun downstreamCancellationEscapesWithoutBeingConvertedOrRetried() = runTest {
        val cancel = CancellationException("synthetic downstream stop")
        val decoder = DeepSeekSseDecoder { throw cancel }
        try { decoder.accept(event("\"取消。\"").toByteArray()); fail("Must cancel") }
        catch (actual: CancellationException) { assertSame(cancel, actual) }
    }
}
