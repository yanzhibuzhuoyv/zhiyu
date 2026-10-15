package com.zhiyu.app.data.ocexpansion

import android.content.Context
import com.zhiyu.app.data.LocalMediaStore
import com.zhiyu.app.data.LocalVisualRepository
import com.zhiyu.app.domain.*
import org.json.JSONObject

/** Independent framing revision deliberately leaves the chat/video visual version unchanged. */
class OcFramingStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("oc_framing_v1", Context.MODE_PRIVATE)
    fun active(chat: String, profile: VisualProfile): OcFramingPolicy? = synchronized(LocalMediaStore.lock) {
        MediaRules.path(chat)
        val raw = prefs.getString("framing_$chat", null) ?: return@synchronized null
        require(LocalMemoryCodec.bytes(raw).size <= 8192) { "取景资料过大" }
        val map = AgnesImageJson.parse(raw) as? Map<*, *> ?: throw IllegalArgumentException("取景资料无效")
        require(map.keys == setOf("schema","original","framing","allow","revision","version","accepted","profile_reference"))
        require(map["schema"] == 1.0)
        val policy = OcFramingRules.check(OcFramingPolicy(asset(map["original"]),
            OcFraming.entries.singleOrNull { it.name == map["framing"] } ?: throw IllegalArgumentException("取景范围无效"),
            map["allow"] as? Boolean ?: throw IllegalArgumentException("扩图偏好无效"), integer(map["revision"]), integer(map["version"]),
            map["accepted"]?.let(::asset), map["profile_reference"] as? String ?: throw IllegalArgumentException("取景参考无效")))
        policy.takeIf { it.visualVersion == profile.visualVersion && it.profileReferenceId == profile.referenceId && profile.mode != "none" }
    }
    fun compareAndSet(chat: String, profile: VisualProfile, expected: OcFramingPolicy?, updated: OcFramingPolicy): Boolean =
        LocalVisualRepository(app).withCurrentProfile(chat) { current ->
            if (current.copy(generation = profile.generation) != profile || active(chat, current) != expected) false else {
                val checked = OcFramingRules.check(updated)
                require(checked.visualVersion == current.visualVersion && checked.profileReferenceId == current.referenceId &&
                    checked.revision == (expected?.revision?.plus(1) ?: 0) &&
                    (expected == null || checked.original == expected.original)) { "取景来源或版本已经变化" }
                if (expected == null) require(checked.accepted == null) { "完整参考尚未确认" }
                check(prefs.edit().putString("framing_$chat", encode(checked)).commit()) { "取景选择未保存" }
                true
            }
        }
    private fun integer(value: Any?): Int {
        require(value is Double && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble()) { "取景版本无效" }
        return value.toInt()
    }
    private fun asset(value: Any?): OcAssetRef {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("取景图片无效")
        require(map.keys == setOf("id","sha","version"))
        return OcAssetRef(map["id"] as? String ?: throw IllegalArgumentException("取景图片无效"),
            map["sha"] as? String ?: throw IllegalArgumentException("取景图片校验无效"), integer(map["version"]))
    }
    private fun encode(policy: OcFramingPolicy): String {
        fun ref(value: OcAssetRef) = JSONObject().put("id",value.id).put("sha",value.sha256).put("version",value.visualVersion)
        return JSONObject().put("schema",1).put("original",ref(policy.original)).put("framing",policy.framing.name)
            .put("allow",policy.allowExpansion).put("revision",policy.revision).put("version",policy.visualVersion)
            .put("accepted",policy.accepted?.let(::ref) ?: JSONObject.NULL).put("profile_reference",policy.profileReferenceId).toString()
    }
}
