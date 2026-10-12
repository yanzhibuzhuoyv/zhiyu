package com.zhiyu.app.data.local.qwen

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A bounded sequential container; the release digest, not the imported header, supplies trust. */
class QwenSinglePackImporter(private val root: File) {
    fun install(
        input: InputStream,
        trustedManifestSha: String,
        checkCancelled: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File = input.use { source ->
        require(readExact(source, 8, checkCancelled).contentEquals(MAGIC)) { "请选择知语离线模型包" }
        val length = ByteBuffer.wrap(readExact(source, 4, checkCancelled)).int.toLong() and 0xffffffffL
        require(length in 1..QwenModelPackManifest.MAX_MANIFEST_BYTES.toLong()) { "模型包清单长度无效" }
        val manifest = QwenModelPackManifest.parse(readExact(source, length.toInt(), checkCancelled), trustedManifestSha)
        val reader = PayloadReader(source, manifest, checkCancelled)
        QwenPackInstaller(root).install(manifest, reader::open, checkCancelled,
            validateSource = reader::finish, progress = progress)
    }

    private class PayloadReader(
        private val source: InputStream,
        private val manifest: QwenModelPackManifest,
        private val cancelled: () -> Unit,
    ) {
        private var index = 0
        private var current: BoundedPayload? = null

        fun open(path: String): InputStream {
            cancelled()
            require(current?.remaining?.let { it == 0L } != false) { "模型文件没有读取完成" }
            val file = manifest.files.getOrNull(index) ?: error("模型包文件顺序无效")
            require(path == file.path) { "模型包文件顺序无效" }
            index++
            return BoundedPayload(source, file.bytes, cancelled).also { current = it }
        }

        fun finish() {
            // An already-installed target still needs to validate the selected new container.
            if (index == 0) manifest.files.forEach { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                open(file.path).use { input ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == file.sha256) {
                    "模型源文件大小或摘要不一致"
                }
            }
            require(index == manifest.files.size && current?.remaining == 0L) { "模型包文件不完整" }
            cancelled()
            require(source.read() == -1) { "模型包包含多余内容" }
        }
    }

    private class BoundedPayload(
        private val source: InputStream,
        var remaining: Long,
        private val cancelled: () -> Unit,
    ) : InputStream() {
        override fun read(): Int {
            cancelled()
            if (remaining == 0L) return -1
            val value = source.read()
            require(value >= 0) { "模型包文件不完整" }
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            cancelled()
            if (remaining == 0L) return -1
            val requested = minOf(length.toLong(), remaining, CHUNK_BYTES.toLong()).toInt()
            val count = source.read(bytes, offset, requested)
            require(count in 1..requested) { "模型包文件不完整或读取没有进展" }
            remaining -= count.toLong()
            return count
        }

        override fun close() = Unit // The next payload shares the same outer stream.
    }

    companion object {
        private val MAGIC = "ZYQPACK1".toByteArray(Charsets.US_ASCII)
        private const val CHUNK_BYTES = 64 * 1024

        private fun readExact(source: InputStream, length: Int, cancelled: () -> Unit): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                cancelled()
                val count = source.read(bytes, offset, minOf(CHUNK_BYTES, length - offset))
                require(count > 0 && count <= length - offset) { "模型包文件不完整或读取没有进展" }
                offset += count
            }
            return bytes
        }
    }
}
