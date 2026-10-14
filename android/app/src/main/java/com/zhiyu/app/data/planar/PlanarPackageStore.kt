package com.zhiyu.app.data.planar

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/** The caller chooses a per-chat and confirmed-source directory. */
class PlanarPackageStore(private val root: File, contractJson: String) {
    private val reader = PlanarPackageReader(contractJson)
    private val active = File(root, "active.planar")
    constructor(context: Context) : this(File(context.filesDir, "planar-v1"), context.assets.open("planar/contract.json").bufferedReader().use { it.readText() })
    @Synchronized
    fun import(input: InputStream, expectedSourceSha: String? = null, checkCancelled: () -> Unit = {}): PlanarDocument =
        save(reader.read(input, expectedSourceSha, checkCancelled), checkCancelled)

    @Synchronized
    fun loadActive(): PlanarDocument? = if (active.exists()) active.inputStream().use { reader.read(it) } else null

    /** Validation and fsync finish before a single atomic rename publishes the new active package. */
    @Synchronized
    fun save(document: PlanarDocument, checkCancelled: () -> Unit = {}): PlanarDocument {
        val bytes = reader.write(document, checkCancelled)
        val snapshot = reader.read(bytes.inputStream(), checkCancelled = checkCancelled)
        checkWork(checkCancelled)
        require(root.isDirectory || root.mkdirs()) { "PLANAR_STORE_DIRECTORY" }
        val pending = Files.createTempFile(root.toPath(), ".pending-", ".planar")
        try {
            FileOutputStream(pending.toFile()).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkWork(checkCancelled)
                    val count = minOf(8192, bytes.size - offset); output.write(bytes, offset, count); offset += count
                }
                output.fd.sync()
            }
            checkWork(checkCancelled)
            Files.move(pending, active.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return snapshot
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    @Synchronized
    fun export(): ByteArray = reader.write(loadActive() ?: throw IllegalStateException("PLANAR_NO_ACTIVE_PACKAGE"))

    private fun checkWork(callback: () -> Unit) {
        callback()
        if (Thread.currentThread().isInterrupted) throw CancellationException("PLANAR_CANCELLED")
    }
}
