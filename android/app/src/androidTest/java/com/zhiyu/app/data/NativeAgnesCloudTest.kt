package com.zhiyu.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiyu.app.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit opt-in. Credentials arrive through private stdin, never test arguments or assets. */
class NativeAgnesCloudTest {
    @Test fun checksExistingSyntheticBackgroundWithoutGeneratingAgain(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloud_agnes") == "enabled")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(base.filesDir, "zhiyu_agnes_probe")
        val key = File(output, "key").readText(Charsets.UTF_8).trim()
        AgnesImageProtocol.checkKey(key)
        val bytes = File(output, "synthetic-background.png").readBytes()
        var calls = 0
        val shape = JSONObject().put("mode", "background_gate_only").put("private_user_media", false)
        val official = AgnesVisionOfficialTransport()
        val gate = BackgroundContentGate({ key }, AgnesVisionTransport { credential, payload ->
            calls++
            val raw = official.exchange(credential, payload)
            try {
                val content = AgnesVisionProtocol.parse(raw)
                shape.put("content_chars", content.length).put("fenced", content.trimStart().startsWith("```"))
                val parsed = try { AgnesImageJson.parse(content) as? Map<*, *> } catch (_: Exception) { null }
                shape.put("strict_json_object", parsed != null)
                shape.put("schema_matches", parsed?.get("schema") == BackgroundContentRules.SCHEMA)
                for (field in listOf("subject_free", "person", "body", "face", "character", "uncertain")) {
                    val value = parsed?.get(field)
                    shape.put("${field}_boolean", value is Boolean)
                    if (value is Boolean) shape.put(field, value)
                }
            } catch (error: ApiFailure) { shape.put("protocol_error", error.errorCode ?: "unknown") }
            raw
        })
        val start = System.nanoTime()
        val allowed = gate.allows(AgnesGeneratedImage(bytes, AgnesImageProtocol.imageMime(bytes)))
        assertEquals(1, calls)
        shape.put("background_gate_allowed", allowed).put("vision_requests", calls)
            .put("gate_ms", (System.nanoTime() - start) / 1_000_000).put("generation_requests", 0)
        File(output, "result.json").writeText(shape.toString(2), Charsets.UTF_8)
    }

    @Test fun officialPhoneClientGeneratesOnceReplaysAndDescribesSyntheticOc() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloud_agnes") == "enabled")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val keyFile = File(base.filesDir, "zhiyu_agnes_probe/key")
        val key = keyFile.readText(Charsets.UTF_8).trim()
        AgnesImageProtocol.checkKey(key)
        NativeV4Context(base).use { context ->
            val chat = UUID.randomUUID().toString().replace("-", "")
            val request = UUID.randomUUID().toString().replace("-", "")
            var calls = 0
            val transport = AgnesOfficialImageTransport()
            val client = AgnesImageClient(AgnesImageTransport { credential, payload ->
                calls++
                val response = transport.exchange(credential, payload)
                recordShape(base, response, calls)
                response
            })
            val repository = LocalVisualRepository(context, client, imageKey = { key })
            val description = "成年虚构角色，深棕色短发，绿色眼睛，米白上衣，左侧一只羽毛翅膀，右侧没有翅膀，手持木制小法杖，正面全身参考图。"
            val start = System.nanoTime()
            val profile = repository.generate(chat, request, description)
            val generationMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(1, calls)
            assertEquals(1, profile.generation.used)
            val original = repository.download(profile.referenceId!!)
            val again = repository.generate(chat, request, description)
            assertEquals(profile.referenceId, again.referenceId)
            assertEquals(1, calls)
            val visionStart = System.nanoTime()
            val observation = AgnesVisionClient({ key }).describe(ChatPhotoImageProcessor().normalize(original))
            assertTrue(observation.isNotBlank())
            val visionMs = (System.nanoTime() - visionStart) / 1_000_000
            val backgroundStart = System.nanoTime()
            val background = client.generate(key, "虚构的温暖阅读角落，午后阳光、书桌、窗帘，水彩绘画，无人、无人体、无文字、无武器。")
            val backgroundMs = (System.nanoTime() - backgroundStart) / 1_000_000
            val gateStart = System.nanoTime()
            val allowed = BackgroundContentGate({ key }).allows(background)
            val report = JSONObject().put("generation_ms", generationMs).put("vision_ms", visionMs)
                .put("background_ms", backgroundMs)
                .put("background_gate_ms", (System.nanoTime() - gateStart) / 1_000_000)
                .put("background_gate_allowed", allowed)
                .put("background_gate_fallback", if (allowed) "fixed_subject_composite" else "local_canvas")
                .put("generation_requests", calls).put("replay_requests", 0)
                .put("oc_bytes", original.size).put("background_bytes", background.bytes.size)
                .put("quota_used", again.generation.used).put("observation", observation)
                .put("transport", "fixed_official_https_no_proxy").put("private_user_media", false)
            val output = File(base.filesDir, "zhiyu_agnes_probe").apply { mkdirs() }
            File(output, "synthetic-oc.png").writeBytes(original)
            File(output, "synthetic-background.png").writeBytes(background.bytes)
            File(output, "result.json").writeText(report.toString(2), Charsets.UTF_8)
        }
    }

    /** Only fixed field types/counts; never raw body, remote URL, credential or Base64. */
    private fun recordShape(context: android.content.Context, response: String, call: Int) {
        val report = JSONObject().put("call", call).put("response_chars", response.length)
        try {
            val root = AgnesImageJson.parse(response) as? Map<*, *>
            val rows = root?.get("data") as? List<*>
            val row = rows?.firstOrNull() as? Map<*, *>
            val encoded = row?.get("b64_json") as? String
            report.put("json_valid", true).put("data_rows", rows?.size ?: -1)
                .put("error_present", root?.get("error") != null)
                .put("error_code_present", root?.get("error_code") != null)
                .put("url_present", row?.get("url") != null)
                .put("base64_chars", encoded?.length ?: -1)
            if (encoded != null && encoded.length <= 8 * 1024 * 1024) {
                try {
                    val bytes = java.util.Base64.getDecoder().decode(encoded)
                    report.put("decoded_bytes", bytes.size)
                    report.put("recognized_png_or_jpeg", runCatching { AgnesImageProtocol.imageMime(bytes) }.isSuccess)
                } catch (_: IllegalArgumentException) { report.put("base64_valid", false) }
            }
        } catch (_: Exception) { report.put("json_valid", false) }
        val output = File(context.filesDir, "zhiyu_agnes_probe").apply { mkdirs() }
        File(output, "response-shape.json").writeText(report.toString(2), Charsets.UTF_8)
    }
}
