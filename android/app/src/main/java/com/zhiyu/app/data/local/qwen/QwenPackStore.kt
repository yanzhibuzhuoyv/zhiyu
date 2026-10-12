package com.zhiyu.app.data.local.qwen

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** This digest is a release trust anchor, never supplied by a downloaded/imported manifest. */
object QwenRelease {
    const val MANIFEST_SHA = "9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6"
    const val IDENTITY = "qwen-edge-android-jni-v1|llama=23b0202a189c44a54625aadcb37a946dd1d6278d|sd=2988060a4338a5dcd94721c964a8e87003c1ac8b|ggml=d25b121ce1d6ae0c7e62ad080460d9fc1e46dd93|fork=2|patch=9bf82bfd37dd1ffd5df28fd32e0a3948ae10ab8f086c886496c01203f300cc95"
    fun manifest(context: Context): QwenModelPackManifest = context.assets.open("qwen/manifest.json").use {
        val bytes = it.readBytes()
        QwenModelPackManifest.parse(bytes, MANIFEST_SHA)
    }
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 29 && android.os.Process.is64Bit() &&
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
}

class QwenPackStore(context: Context) {
    private val app = context.applicationContext ?: context
    private val root get() = File(app.filesDir.canonicalFile, "qwen_models").apply { check(mkdirs() || isDirectory) }
    fun installed(): File? {
        val path = File(root, QwenRelease.MANIFEST_SHA)
        if (!path.isDirectory) return null
        require(path.canonicalFile == path.absoluteFile && !java.nio.file.Files.isSymbolicLink(path.toPath()))
        val marker = File(path, "verified.sha256")
        require(marker.length() == 64L && marker.readText() == QwenRelease.MANIFEST_SHA) { "本机模型安装未完成" }
        val manifest = QwenRelease.manifest(app)
        require(manifest.files.all { File(path, it.path).isFile && File(path, it.path).length() == it.bytes }) { "本机模型文件不完整" }
        return path
    }
    fun verify(cancelled: () -> Unit = {}): QwenVerifiedModelPack {
        val manifest = QwenRelease.manifest(app)
        return QwenModelPackVerifier.verify(installed() ?: error("请先导入学生模型包"), manifest, manifest.runtime, cancelled)
    }
    suspend fun import(tree: Uri, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(QwenRelease.supported()) { "本机生图需要 Android 10 及 64 位设备" }
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "请选择模型包文件夹" }
        val manifest = QwenRelease.manifest(app)
        QwenPackCapacity.requireStorage(manifest, StatFs(root.path).availableBytes, 1, 512L * 1024 * 1024)
        val folder = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
        val entries = mutableMapOf<String, Uri>()
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1)
                require(!entries.containsKey(name)) { "模型文件夹存在重名文件" }
                entries[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        } ?: error("无法读取模型文件夹")
        val coroutine = currentCoroutineContext()
        var lastProgress = 0L
        QwenPackInstaller(root).install(manifest, { name ->
            app.contentResolver.openInputStream(entries[name] ?: error("模型包缺少 $name")) ?: error("无法读取模型文件")
        }, coroutine::ensureActive) { done, total ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (done == total || now - lastProgress >= 100) { lastProgress = now; progress(done,total) }
        }
    }
}
