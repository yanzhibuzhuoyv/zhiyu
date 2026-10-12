package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatPhotoAnalyzerTest {
    private val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)
    private val photo = ChatPhotoPayload("a".repeat(64), ChatPhotoRules.sha(bytes), "image/png", bytes)

    @Test fun nativeMultimodalDoesNotInvokeFallbackServices() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { error("must not call OCR") }, ChatPhotoVision { error("must not call remote") })
        val result = analyzer.prepare(photo, "kimi", "kimi-k2.6")
        assertTrue(result.nativeVision)
        assertTrue(result.observations.isEmpty())
    }
    @Test fun deepseekFlashReceivesPixelsInsteadOfOcrOrAgnesSummary() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { error("must not call OCR") }, ChatPhotoVision { error("must not call Agnes") })
        val result = analyzer.prepare(photo, "deepseek", "deepseek-flash")
        assertTrue(result.nativeVision)
        assertTrue(result.observations.isEmpty())
        assertTrue(result.unavailable.isEmpty())
    }
    @Test fun independentRawObservationsStayBoundToOriginalHash() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { "字迹可能有误" }, ChatPhotoVision { "像是六只翅膀，也可能被遮挡" })
        val result = analyzer.prepare(photo, "deepseek", "deepseek-v4-pro")
        assertEquals(listOf("ocr", "vision"), result.observations.map { it.kind })
        assertEquals(listOf(photo.originalSha256, photo.originalSha256), result.observations.map { it.originalSha256 })
        assertEquals("像是六只翅膀，也可能被遮挡", result.observations[1].text)
        assertTrue(result.unavailable.isEmpty())
    }
    @Test fun unconfiguredVisionKeepsOcrAndReportsUnavailableCapability() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { "请吃饭" }, ChatPhotoVision { throw ApiFailure(503, "unconfigured") })
        val result = analyzer.prepare(photo, "deepseek", "deepseek-v4-pro")
        assertEquals(1, result.observations.size)
        assertEquals(setOf("vision"), result.unavailable)
        assertTrue(result.context().contains("不是用户原话"))
    }
    @Test fun noUsableObservationExplicitlyPreventsPretendingToSeeImage() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { "" }, ChatPhotoVision { throw ApiFailure(0, "no network") })
        val result = analyzer.prepare(photo, "deepseek", "deepseek-v4-pro")
        assertTrue(result.observations.isEmpty())
        assertTrue(result.context().contains("不要假装看见"))
    }
    @Test fun cancellationPropagatesToCallerAndSibling() = runTest {
        val analyzer = ChatPhotoAnalyzer(ChatPhotoOcr { throw CancellationException("stop") }, ChatPhotoVision { "" })
        try { analyzer.prepare(photo, "deepseek", "deepseek-v4-pro"); fail() }
        catch (_: CancellationException) { }
    }
}
