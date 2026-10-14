package com.zhiyu.app.domain

import java.net.InetAddress
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class Image2ProtocolTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS/0AAAAASUVORK5CYII=")

    @Test fun observedMultipartFieldsUseVerifiedModelAndImmutableReference() {
        val input = png.copyOf()
        val request = Image2Protocol.request("same OC drinking tea", input)
        input[0] = 0
        assertEquals("https://cn-api.ooapi.cc/v1/images/edits", request.endpoint)
        assertEquals("gpt-image-2.5-flare", request.fields["model"])
        assertEquals("1", request.fields["n"])
        assertEquals("1024x1024", request.fields["size"])
        assertEquals("medium", request.fields["quality"])
        assertEquals("png", request.fields["output_format"])
        assertFalse(request.fields.containsKey("ratio"))
        assertFalse(request.fields.containsKey("extra_body"))
        assertArrayEquals(png, request.reference)
    }

    @Test fun textOnlyContractIsJsonAndContainsNoAgnesExtension() {
        val request = Image2Protocol.request("new OC portrait", model = "gpt-image-2.5-sunburst")
        val json = AgnesImageJson.parse(request.json) as Map<*, *>
        assertEquals("https://cn-api.ooapi.cc/v1/images/generations", request.endpoint)
        assertEquals("gpt-image-2.5-sunburst", json["model"])
        assertEquals(1.0, json["n"])
        assertFalse(json.containsKey("return_base64"))
        assertNull(request.reference)
    }

    @Test fun invalidInputsFailBeforeNetwork() {
        listOf("", " ", "key\nInjected: yes", "x".repeat(4097)).forEach {
            assertThrows(IllegalArgumentException::class.java) { Image2Protocol.checkKey(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { Image2Protocol.request("", png) }
        assertThrows(IllegalArgumentException::class.java) { Image2Protocol.request("x".repeat(4001), png) }
        assertThrows(IllegalArgumentException::class.java) { Image2Protocol.request("portrait", png, "unverified-model") }
        assertThrows(IllegalArgumentException::class.java) { Image2Protocol.request("portrait", byteArrayOf(1, 2)) }
    }

    @Test fun oneBoundedInlineImageIsPreferredAndImmutable() {
        val json = """{"created":123,"size":"1070x1470","data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}","url":"http://127.0.0.1/private"}],"usage":{"total_tokens":2051}}"""
        val image = (Image2Protocol.parse(json) as Image2Output.Inline).image
        assertEquals("image/png", image.mimeType)
        val bytes = image.bytes; bytes[0] = 0
        assertArrayEquals(png, image.bytes)
    }

    @Test fun malformedAmbiguousAndOversizedInlineResponsesFailClosed() {
        val encoded = Base64.getEncoder().encodeToString(png)
        listOf("{}", """{"data":[]}""", """{"data":[{"b64_json":"!invalid!"}]}""",
            """{"data":[{"b64_json":"$encoded"},{"b64_json":"$encoded"}]}""",
            """{"data":[{"b64_json":"$encoded"}],"data":[]}""",
            """{"error":{"message":"secret"},"data":[{"b64_json":"$encoded"}]}""").forEach {
            assertThrows(ApiFailure::class.java) { Image2Protocol.parse(it) }
        }
        assertThrows(ApiFailure::class.java) { Image2Protocol.parse("x".repeat(Image2Protocol.MAX_RESPONSE_BYTES + 1)) }
    }

    @Test fun urlOutputRequiresHttpsPublicHostNoUserInfoFragmentOrCustomPort() {
        val url = "https://images.example.com/result.png?signature=synthetic"
        assertEquals(url, (Image2Protocol.parse("""{"data":[{"url":"$url"}]}""") as Image2Output.Url).value)
        listOf("http://images.example.com/result.png", "https://key@images.example.com/result.png",
            "https://images.example.com:444/result.png", "https://images.example.com/result.png#fragment",
            "https://localhost/result.png", "https://127.0.0.1/result.png", "https://[::1]/result.png",
            "https://169.254.169.254/latest/meta-data", "https://10.0.0.1/image", "https://x.local/image").forEach {
            assertThrows(IllegalArgumentException::class.java) { Image2Protocol.checkDownloadUrl(it) }
        }
    }

    @Test fun dnsRejectsPrivateLoopbackLinkLocalCarrierGradeAndIpv6LocalAddresses() {
        listOf("127.0.0.1", "10.2.3.4", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1").forEach {
            assertFalse(it, Image2Protocol.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(Image2Protocol.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(Image2Protocol.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun observedTopLevelBalanceAndNestedAuthErrorsAreSanitized() {
        val balance = Image2Protocol.statusFailure(403, """{"code":"INSUFFICIENT_BALANCE","message":"Insufficient account balance secret-key"}""")
        assertEquals("image_balance_insufficient", balance.errorCode)
        assertFalse(balance.message!!.contains("secret-key"))
        assertNull(balance.cause)
        val auth = Image2Protocol.statusFailure(401, """{"error":{"type":"invalid_request_error","message":"secret-key"}}""")
        assertEquals("image_auth_failed", auth.errorCode)
        assertEquals("image_request_unknown", Image2Protocol.statusFailure(502).errorCode)
        assertEquals("image_redirect_rejected", Image2Protocol.statusFailure(302).errorCode)
    }
}
