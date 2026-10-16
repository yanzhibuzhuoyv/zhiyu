package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.MediaAttachment
import com.zhiyu.app.domain.MediaRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exact old metadata/bytes, intentionally bypasses the retired *new import* API for migration tests. */
internal fun legacySticker(context: Context, bytes: ByteArray, source: String = "旧表情素材",
    day: String? = null, tags: List<String> = emptyList(), version: Int = 0): MediaAttachment {
    val id = LocalMediaStore.identifier(); val sha = LocalMediaStore.sha(bytes)
    val attachment = MediaRules.check(MediaAttachment(id, "sticker", MediaRules.path(id), "image/png", sha, day, version, source))
    val folder = File(context.filesDir, "local_media/sticker")
    LocalMediaStore.write(File(folder, "blobs/$sha.bin"), bytes)
    val metadata = JSONObject().put("id", id).put("kind", "sticker").put("mime", "image/png")
        .put("sha256", sha).put("version", version).put("day", day).put("source", source)
        .put("description", "旧版迁移测试").put("tags", JSONArray(tags)).put("purpose", "library")
    LocalMediaStore.write(File(folder, "$id.json"), metadata.toString().toByteArray(Charsets.UTF_8))
    return attachment
}
