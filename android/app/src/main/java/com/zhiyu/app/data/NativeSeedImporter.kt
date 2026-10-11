package com.zhiyu.app.data

import android.content.Context
import org.json.JSONArray

class NativeSeedImporter(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("native_media_seed", Context.MODE_PRIVATE)
    fun install() = synchronized(serial) {
        if (preferences.getInt("version", 0) >= 1) return@synchronized
        val manifest = appContext.assets.open("media_seed/manifest.json").use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
        val store = LocalMediaStore(appContext)
        for (index in 0 until manifest.length()) {
            val item = manifest.getJSONObject(index)
            val name = item.getString("file")
            require(Regex("[a-zA-Z0-9_.-]+\\.(png|jpg|gif|webp)").matches(name)) { "首批素材文件名无效" }
            val bytes = appContext.assets.open("media_seed/$name").use { it.readBytes() }
            val tags = item.getJSONArray("tags")
            store.ingest(item.getString("kind"), bytes, item.getString("source"), description = item.getString("description"),
                tags = List(tags.length()) { tags.getString(it) })
        }
        check(preferences.edit().putInt("version", 1).commit()) { "首批素材安装状态保存失败" }
    }
    companion object { private val serial = Any() }
}
