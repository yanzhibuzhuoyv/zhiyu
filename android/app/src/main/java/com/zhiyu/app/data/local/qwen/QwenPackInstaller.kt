package com.zhiyu.app.data.local.qwen

import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Streams into an owned staging directory; the active pack is never edited in place. */
class QwenPackInstaller(private val root: File) {
    fun install(manifest: QwenModelPackManifest, open: (String) -> InputStream,
        checkCancelled: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): File = synchronized(lock) {
        require(root.absoluteFile == root.canonicalFile && root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        val target = File(root, manifest.manifestSha256)
        if (target.exists()) {
            QwenModelPackVerifier.verify(target, manifest, manifest.runtime, checkCancelled)
            progress(manifest.totalBytes, manifest.totalBytes)
            return@synchronized target
        }
        val staging = File(root, "staging-${UUID.randomUUID()}")
        check(staging.mkdir()) { "无法创建模型安装目录" }
        try {
            var copied = 0L
            manifest.files.forEach { spec ->
                checkCancelled()
                val output = File(staging, spec.path)
                check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
                copy(spec, open, output, checkCancelled) { count ->
                    copied += count
                    progress(copied, manifest.totalBytes)
                }
            }
            QwenModelPackVerifier.verify(staging, manifest, manifest.runtime, checkCancelled)
            FileOutputStream(File(staging, "verified.sha256")).use {
                it.write(manifest.manifestSha256.toByteArray(Charsets.US_ASCII)); it.fd.sync()
            }
            checkCancelled()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target
        } finally {
            if (staging.exists()) removeOwnedStaging(staging)
        }
    }

    private fun copy(spec: QwenModelFile, open: (String) -> InputStream, target: File,
        cancelled: () -> Unit, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        require(target.createNewFile()) { "模型安装目标已存在" }
        open(spec.path).use { source -> FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancelled()
                val count = source.read(buffer)
                if (count < 0) break
                require(count > 0) { "模型输入读取没有进展" }
                copied = Math.addExact(copied, count.toLong())
                require(copied <= spec.bytes) { "模型源文件超出清单大小" }
                output.write(buffer, 0, count); digest.update(buffer, 0, count); progress(count.toLong())
            }
            require(copied == spec.bytes && hex(digest.digest()) == spec.sha256) { "模型源文件大小或摘要不一致" }
            output.fd.sync()
        } }
    }

    private fun removeOwnedStaging(directory: File) {
        val base = root.toPath()
        val owned = directory.toPath()
        require(owned.parent == base && directory.name.startsWith("staging-") &&
            !Files.isSymbolicLink(owned) && owned.toRealPath() == owned)
        Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.startsWith(owned))
            Files.deleteIfExists(path)
        } }
    }
    companion object {
        private val lock = Any()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
