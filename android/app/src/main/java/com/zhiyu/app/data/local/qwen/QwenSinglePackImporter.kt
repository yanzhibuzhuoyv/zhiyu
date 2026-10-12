package com.zhiyu.app.data.local.qwen

import java.io.File
import java.io.InputStream

/** ZYQPACK1 streaming import. Production implementation follows the actual RED run. */
class QwenSinglePackImporter(private val root: File) {
    fun install(
        input: InputStream,
        trustedManifestSha: String,
        checkCancelled: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        // TODO: Implement only after the independent importer tests have failed on this stub.
        throw UnsupportedOperationException("TDD pending: ZYQPACK1 importer is not implemented")
    }
}
