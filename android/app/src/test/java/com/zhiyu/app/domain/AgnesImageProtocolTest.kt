package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AgnesImageProtocolTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2)

    @Test fun requestUsesOfficialModelAndKeepsReferenceInsideExtraBody() {
        val json = AgnesImageProtocol.payload("保留角色\n更换场景", png)
        assertTrue(json.contains("\"model\":\"agnes-image-2.5-flash\""))
        assertTrue(json.contains("\"size\":\"1K\""))
        assertTrue(json.contains("\"ratio\":\"3:4\""))
        assertTrue(json.contains("\"extra_body\":{\"response_format\":\"b64_json\",\"image\":[\"data:image/png;base64,"))
        assertTrue(json.contains("保留角色\\n更换场景"))
        assertFalse(json.contains("Authorization"))
        assertFalse(json.contains("Bearer"))
        assertFalse(json.contains("https://"))
    }

    @Test fun noReferenceUsesBase64OutputAndCannotInjectJsonThroughPrompt() {
        val json = AgnesImageProtocol.payload("猫\"},\"model\":\"evil")
        assertTrue(json.contains("\"return_base64\":true"))
        assertFalse(json.contains("\"image\":"))
        assertTrue(json.contains("猫\\\"},\\\"model\\\":\\\"evil"))
    }

    @Test fun invalidPromptReferenceSizeAndRatioAreRejectedBeforeTransmission() {
        listOf("", " ", "x".repeat(4001), "\ud800").forEach { prompt ->
            assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload(prompt) }
        }
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload("test", ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload("test", ByteArray(5 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload("test", "GIF89a".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload("test", size = "8192x8192") }
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.payload("test", ratio = "unknown") }
    }

    @Test fun onlyPrintableUnspacedCredentialsAreAccepted() {
        AgnesImageProtocol.checkKey("synthetic-agnes-key")
        listOf("", "abc def", "abc\nHeader:secret", "abc\u007f", "中文", "x".repeat(4097)).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.checkKey(key) }
        }
    }

    @Test fun base64PngAndJpegReturnDefensiveCopies() {
        val result = AgnesImageProtocol.parse(response(png))
        assertEquals("image/png", result.mimeType)
        assertArrayEquals(png, result.bytes)
        result.bytes[0] = 0
        assertArrayEquals(png, result.bytes)
        assertFalse(result.toString().contains(Base64.getEncoder().encodeToString(png)))
        val jpeg = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 1, 2)
        assertEquals("image/jpeg", AgnesImageProtocol.parse(response(jpeg)).mimeType)
    }

    @Test fun validBase64IsAcceptedAndTheAccompanyingRemoteUrlIsIgnored() {
        val raw = "{\"data\":[{\"b64_json\":\"${Base64.getEncoder().encodeToString(png)}\",\"url\":\"https://example.test/image\"}]}"
        assertArrayEquals(png, AgnesImageProtocol.parse(raw).bytes)
    }

    @Test fun remoteUrlNeverRescuesMissingOrInvalidBase64() {
        listOf("{\"data\":[{\"url\":\"https://example.test/picture.png\"}]}",
            "{\"data\":[{\"b64_json\":\"@@@@\",\"url\":\"https://example.test/image\"}]}").forEach { raw ->
            assertThrows(ApiFailure::class.java) { AgnesImageProtocol.parse(raw) }
        }
    }

    @Test fun malformedAmbiguousAndNonImageResponsesFailWithFixedPublicMessage() {
        listOf("", "not-json", "{}", "{\"data\":[]}", "{\"data\":[null]}",
            "{\"data\":[{\"b64_json\":3}]}", "{\"data\":[{\"b64_json\":\"@@@@\"}]}",
            "{\"data\":[{\"b64_json\":\"R0lGODlh\"}]}",
            "{\"data\":[],\"data\":[{\"b64_json\":\"x\"}]}",
            "{\"data\":[{\"b64_json\":\"x\",\"b64_json\":\"y\"}]}",
            "{\"error\":{\"message\":\"sensitive-provider-body\"},\"data\":[]}",
            "{\"data\":[],\"value\":NaN}", "{\"data\":[],\"value\":1e999}",
            "{\"data\":[],\"value\":\"\\ud800\"}", "${response(png)} trailing").forEach { raw ->
            val error = assertThrows(ApiFailure::class.java) { AgnesImageProtocol.parse(raw) }
            assertEquals("image_response_invalid", error.errorCode)
            assertFalse(error.message!!.contains("sensitive-provider-body"))
        }
    }

    @Test fun multipleResultsAndOversizedDecodedImageAreRejected() {
        val row = "{\"b64_json\":\"${Base64.getEncoder().encodeToString(png)}\"}"
        assertThrows(ApiFailure::class.java) { AgnesImageProtocol.parse("{\"data\":[$row,$row]}") }
        val oversized = ByteArray(5 * 1024 * 1024 + 1).also { png.copyInto(it) }
        assertThrows(ApiFailure::class.java) { AgnesImageProtocol.parse(response(oversized)) }
    }

    @Test fun responseReaderEnforcesBytesInsteadOfAdvertisedContentLength() {
        assertArrayEquals(byteArrayOf(1, 2, 3), AgnesImageProtocol.readBounded(byteArrayOf(1, 2, 3).inputStream(), 3))
        assertThrows(IllegalArgumentException::class.java) { AgnesImageProtocol.readBounded(ByteArray(4).inputStream(), 3) }
    }

    private fun response(bytes: ByteArray) = "{\"created\":1,\"data\":[{\"b64_json\":\"${Base64.getEncoder().encodeToString(bytes)}\",\"url\":null}]}"
}
