package com.zhiyu.app.data.local.qwen

import com.zhiyu.app.domain.AgnesImageJson
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

enum class QwenFileRole(val wireId: String) {
    STUDENT("student"), ADAPTER("adapter"), DIT("dit"), VAE("vae")
}

data class QwenModelFile(val role: QwenFileRole, val path: String, val bytes: Long, val sha256: String)
data class QwenRuntimeLock(val llamaCppCommit: String, val stableDiffusionCppCommit: String, val bridgeVersion: Int)

/** Pinned model metadata. It verifies no tensors and does not imply successful Android inference. */
class QwenModelPackManifest private constructor(
    val packId: String,
    files: List<QwenModelFile>,
    val runtime: QwenRuntimeLock,
    val manifestSha256: String,
) {
    val files: List<QwenModelFile> = Collections.unmodifiableList(files.toList())
    val layers: List<Int> = Collections.unmodifiableList(HIDDEN_LAYERS.toList())
    val sigmas: List<Double> = Collections.unmodifiableList(TURBO_SIGMAS.toList())
    val totalBytes: Long = files.fold(0L) { sum, file -> Math.addExact(sum, file.bytes) }

    companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = 256L * 1024 * 1024 * 1024
        private val SHA = Regex("[a-f0-9]{64}")
        private val COMMIT = Regex("[a-f0-9]{40}")
        private val HIDDEN_LAYERS = listOf(-1, -4, -7, -10, -13, -16)
        private val TURBO_SIGMAS = listOf(1.0, .978453, .95418, .926626, .89508, .845148, .704534, .414568, 0.0)

        /** trustedSha256 must come from a release record, never from the imported manifest itself. */
        fun parse(bytes: ByteArray, trustedSha256: String): QwenModelPackManifest {
            require(bytes.size in 1..MAX_MANIFEST_BYTES && SHA.matches(trustedSha256)) { "模型包清单或信任摘要无效" }
            val copy = bytes.copyOf()
            require(sha256(copy) == trustedSha256) { "模型包清单与受信记录不一致" }
            val root = objectFields(AgnesImageJson.parse(utf8(copy)), setOf("format_version", "pack_id", "architecture",
                "capabilities", "files", "runtime", "conditioning", "sampling"))
            require(integer(root["format_version"]) == 1L && root["architecture"] == "qwen-image-2.1-turbo") {
                "模型包架构或版本不受支持"
            }
            require(root["capabilities"] == listOf("text_to_image_background")) { "模型包仅支持背景文生图" }
            val id = root["pack_id"] as? String ?: throw IllegalArgumentException("模型包标识无效")
            require(Regex("[a-z0-9_-]{1,64}").matches(id)) { "模型包标识无效" }
            val files = parseFiles(root["files"])
            val runtime = parseRuntime(root["runtime"])
            checkConditioning(root["conditioning"])
            checkSampling(root["sampling"])
            return QwenModelPackManifest(id, files, runtime, trustedSha256)
        }

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun parseFiles(value: Any?): List<QwenModelFile> {
            val entries = value as? List<*> ?: throw IllegalArgumentException("模型文件清单无效")
            require(entries.size == 4) { "模型包必须包含四种模型文件" }
            val files = entries.map { entry ->
                val item = objectFields(entry, setOf("role", "path", "bytes", "sha256"))
                val role = QwenFileRole.entries.singleOrNull { it.wireId == item["role"] }
                    ?: throw IllegalArgumentException("模型文件角色无效")
                val path = item["path"] as? String ?: throw IllegalArgumentException("模型文件路径无效")
                requireSafePath(path)
                val count = integer(item["bytes"])
                require(count in 1..MAX_FILE_BYTES) { "模型文件大小无效" }
                val sha = item["sha256"] as? String ?: throw IllegalArgumentException("模型文件摘要无效")
                require(SHA.matches(sha)) { "模型文件摘要无效" }
                QwenModelFile(role, path, count, sha)
            }
            require(files.map { it.role }.toSet() == QwenFileRole.entries.toSet()) { "模型文件角色重复或缺失" }
            require(files.map { it.path.lowercase(java.util.Locale.ROOT) }.toSet().size == 4) { "模型文件路径重复" }
            return files
        }

        internal fun requireSafePath(path: String) {
            require(path.length in 1..240 && !path.startsWith("/")) { "模型文件路径无效" }
            require(path.split('/').all { it !in setOf("", ".", "..") && Regex("[A-Za-z0-9_.-]{1,100}").matches(it) }) {
                "模型文件路径无效"
            }
        }

        private fun parseRuntime(value: Any?): QwenRuntimeLock {
            val runtime = objectFields(value, setOf("llama_cpp_commit", "stable_diffusion_cpp_commit", "bridge_version"))
            val llama = runtime["llama_cpp_commit"] as? String ?: throw IllegalArgumentException("运行时锁无效")
            val diffusion = runtime["stable_diffusion_cpp_commit"] as? String ?: throw IllegalArgumentException("运行时锁无效")
            require(COMMIT.matches(llama) && COMMIT.matches(diffusion) && integer(runtime["bridge_version"]) == 1L) {
                "运行时版本不受支持"
            }
            return QwenRuntimeLock(llama, diffusion, 1)
        }

        private fun checkConditioning(value: Any?) {
            val condition = objectFields(value, setOf("adapter_format", "in_dim", "out_dim", "layers", "drop_tokens"))
            require(condition["adapter_format"] == "zqadap1_f32_le" && integer(condition["in_dim"]) == 6144L &&
                integer(condition["out_dim"]) == 4096L && integer(condition["drop_tokens"]) == 14L) { "文本条件桥接不匹配" }
            val layers = condition["layers"] as? List<*> ?: throw IllegalArgumentException("文本隐藏层无效")
            require(layers.map { integer(it) } == HIDDEN_LAYERS.map { it.toLong() }) { "文本隐藏层不匹配" }
        }

        private fun checkSampling(value: Any?) {
            val sampling = objectFields(value, setOf("width", "height", "steps", "sigmas"))
            require(integer(sampling["width"]) == 512L && integer(sampling["height"]) == 512L &&
                integer(sampling["steps"]) == 8L) { "本机原型仅支持 512 像素、8 步" }
            val sigmas = sampling["sigmas"] as? List<*> ?: throw IllegalArgumentException("采样网格无效")
            require(sigmas.size == TURBO_SIGMAS.size) { "采样网格缺少边界" }
            require(sigmas.zip(TURBO_SIGMAS).all { (value, expected) ->
                value is Double && value.isFinite() && kotlin.math.abs(value - expected) <= 1e-9
            }) { "采样网格与 Turbo 导出不匹配" }
        }

        private fun objectFields(value: Any?, fields: Set<String>): Map<*, *> {
            val result = value as? Map<*, *> ?: throw IllegalArgumentException("模型清单对象无效")
            require(result.keys == fields) { "模型清单存在未知、缺失或重复字段" }
            return result
        }

        private fun integer(value: Any?): Long {
            val number = value as? Double ?: throw IllegalArgumentException("模型清单整数无效")
            require(number.isFinite() && number >= -9_007_199_254_740_991.0 && number <= 9_007_199_254_740_991.0 &&
                number == number.toLong().toDouble()) { "模型清单整数无效" }
            return number.toLong()
        }

        private fun utf8(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) { throw IllegalArgumentException("模型清单编码无效", failure) }
    }
}
