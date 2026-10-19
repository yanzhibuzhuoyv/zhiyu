package com.zhiyu.app.data

import android.content.Context
import com.zhiyu.app.domain.ChatPhotoRules
import com.zhiyu.app.domain.LocalMemoryCodec
import org.json.JSONArray
import org.json.JSONObject

/** Only user-confirmed attribution hashes. No pixels, captions, names, or recalled user facts. */
internal class OcPhotoOwnershipStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("oc_photo_ownership_v1", Context.MODE_PRIVATE)
    fun confirmed(snapshot: OcPhotoIdentitySnapshot, photoSha: String): Boolean = synchronized(lock) {
        ChatPhotoRules.digest(photoSha)
        read().any { it.scope == scope(snapshot) && it.photo == photoSha }
    }
    fun save(confirmation: OcPhotoConfirmation) = synchronized(lock) {
        val key = scope(confirmation.snapshot)
        val before = read().filterNot { it.scope == key && it.photo == confirmation.photoSha }
        val after = (before + if (confirmation.confirmed) listOf(Entry(key, confirmation.photoSha)) else emptyList()).takeLast(MAX_ROWS)
        val encoded = JSONObject().put("schema", 1).put("entries", JSONArray(after.map {
            JSONObject().put("scope", it.scope).put("photo_sha256", it.photo)
        })).toString()
        require(encoded.length <= MAX_CHARS) { "图片归属标注超过范围" }
        check(preferences.edit().putString("index", encoded).commit()) { "图片归属未能保存，聊天原图仍然保留" }
    }
    private data class Entry(val scope: String, val photo: String)
    private fun read(): List<Entry> {
        val raw = preferences.getString("index", null) ?: return emptyList()
        require(raw.length in 1..MAX_CHARS) { "图片归属标注损坏" }
        val value = JSONObject(raw); require(value.getInt("schema") == 1)
        val rows = value.getJSONArray("entries"); require(rows.length() <= MAX_ROWS)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            Entry(row.getString("scope"), row.getString("photo_sha256")).also {
                ChatPhotoRules.digest(it.scope); ChatPhotoRules.digest(it.photo)
            }
        }.also { require(it.distinct().size == it.size) { "图片归属标注重复" } }
    }
    private fun scope(snapshot: OcPhotoIdentitySnapshot): String = ChatPhotoRules.sha(LocalMemoryCodec.bytes(
        listOf("oc-image-owner-v1", snapshot.visualVersion.toString(), snapshot.reference.id,
            snapshot.reference.sha256, snapshot.reference.visualVersion.toString(),
            snapshot.original?.sha256.orEmpty(), snapshot.creatorPackageSha.orEmpty()).joinToString(":")))
    private companion object {
        val lock = Any()
        const val MAX_ROWS = 64
        const val MAX_CHARS = 16_384
    }
}
