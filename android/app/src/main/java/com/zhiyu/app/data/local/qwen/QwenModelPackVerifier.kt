package com.zhiyu.app.data.local.qwen

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections

/** Integrity result for private, immutable installed files. Native loaders still validate tensor formats. */
class QwenVerifiedModelPack internal constructor(
    val manifest: QwenModelPackManifest,
    files: Map<QwenFileRole, File>,
) {
    val files: Map<QwenFileRole, File> = Collections.unmodifiableMap(files.toMap())
}

/** Never reads a whole weight into RAM, downloads a model or modifies a source file. */
object QwenModelPackVerifier {
    private const val CHUNK_BYTES = 64 * 1024

    fun verify(root: File, manifest: QwenModelPackManifest, runtime: QwenRuntimeLock,
        checkCancelled: () -> Unit = {}): QwenVerifiedModelPack {
        require(runtime == manifest.runtime) { "模型包与本机运行时不匹配" }
        val directory = root.toPath().toAbsolutePath()
        require(directory == directory.normalize() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directory) && directory.toRealPath() == directory) { "模型包目录必须是明确的本机私有目录" }
        val verified = manifest.files.associate { spec ->
            checkCancelled()
            val path = resolveFile(directory, spec.path)
            verifyFile(directory, path, spec, checkCancelled)
            spec.role to path.toFile()
        }
        checkCancelled()
        return QwenVerifiedModelPack(manifest, verified)
    }

    private fun resolveFile(root: Path, relative: String): Path {
        QwenModelPackManifest.requireSafePath(relative)
        val path = root.resolve(relative)
        require(path.normalize() == path && path.startsWith(root)) { "模型文件超出私有目录" }
        var current = root
        relative.split('/').forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "模型文件不能通过符号链接加载" }
        }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "模型文件不存在或不是普通文件" }
        require(path.toRealPath() == path) { "模型文件路径已变化" }
        return path
    }

    private fun verifyFile(root: Path, path: Path, spec: QwenModelFile, checkCancelled: () -> Unit) {
        val before = attributes(path)
        require(before.size() == spec.bytes) { "模型文件大小与清单不一致" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(CHUNK_BYTES)
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                require(read > 0) { "模型文件读取没有进展" }
                count = Math.addExact(count, read.toLong())
                require(count <= spec.bytes) { "模型文件在验证期间变大" }
                digest.update(buffer, 0, read)
            }
        }
        checkCancelled()
        require(resolveFile(root, spec.path) == path) { "模型文件路径在验证期间变化" }
        val after = attributes(path)
        require(count == spec.bytes && after.size() == before.size() && after.fileKey() == before.fileKey() &&
            after.lastModifiedTime() == before.lastModifiedTime()) { "模型文件在验证期间变化" }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        require(sha == spec.sha256) { "模型文件内容与清单不一致" }
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path,
        BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
}
