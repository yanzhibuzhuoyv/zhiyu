package com.zhiyu.app.data

import android.content.Context
import android.graphics.Bitmap
import com.zhiyu.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class OcSceneImage(val ocId: String, val revision: Int, val packageSha: String, val visualVersion: Int,
    val sourceSha: String?, png: ByteArray) {
    private val bytes = png.copyOf()
    val png: ByteArray get() = bytes.copyOf()
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Render a saved OC package for a daily scene. Committers must recheck this package SHA/version. */
class CharacterSceneRenderer(context: Context, private val loadImage: suspend (OcAssetRef) -> ByteArray) {
    private val store = OcCreatorStore(context)
    suspend fun render(chatId: String, expectedPackageSha: String, scene: OcScene,
        width: Int = 768, height: Int = 1024, background: Bitmap? = null): OcSceneImage {
        val snapshot = withContext(Dispatchers.IO) { store.read(chatId) } ?: throw IllegalArgumentException("请先保存角色外观")
        require(snapshot.packageSha == expectedPackageSha && snapshot.identity.confirmed) { "角色已更新，请重新生成场景" }
        val identity = snapshot.identity
        var source: Bitmap? = null
        try {
            if (identity.route == "photo_subject") {
                val ref = identity.photoSource ?: throw IllegalArgumentException("角色原图缺失")
                val bytes = loadImage(ref)
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(sha == ref.sha256) { "角色原图校验失败" }
                source = ownedOcBitmap { LocalPortraitProcessor.decode(bytes) }
            }
            val png = withContext(Dispatchers.IO) {
                when (identity.route) {
                    "avatar_3d" -> {
                        // Transparent fixed geometry may be composited by the caller over a background.
                        AvatarStillRenderer.png(identity, scene, width, height)
                    }
                    "parts_2d", "photo_subject" -> OcFlatRenderer.png(identity, scene, width, height, source, background)
                    else -> throw IllegalArgumentException("请先选择角色制作方式")
                }
            }
            return OcSceneImage(identity.ocId, identity.revision, snapshot.packageSha,
                checkNotNull(identity.reference).visualVersion, identity.photoSource?.sha256, png)
        } finally { source?.recycle() }
    }
}
