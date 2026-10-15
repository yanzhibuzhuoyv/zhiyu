package com.zhiyu.app.domain

/** Original pixels remain immutable. An accepted extension is an additional daily-photo reference. */
enum class OcFraming { ORIGINAL, HEAD, BUST, FULL }
data class OcFramingPolicy(val original: OcAssetRef, val framing: OcFraming, val allowExpansion: Boolean,
    val revision: Int, val visualVersion: Int, val accepted: OcAssetRef? = null,
    val profileReferenceId: String = original.id)

object OcFramingRules {
    fun check(policy: OcFramingPolicy): OcFramingPolicy {
        require(policy.revision >= 0 && policy.visualVersion >= 0) { "取景版本无效" }
        MediaRules.path(policy.profileReferenceId)
        listOfNotNull(policy.original, policy.accepted).forEach {
            MediaRules.path(it.id); ChatPhotoRules.digest(it.sha256)
            require(it.visualVersion in 0..policy.visualVersion) { "取景参考版本无效" }
        }
        require(policy.accepted == null || policy.allowExpansion && policy.framing in setOf(OcFraming.HEAD, OcFraming.BUST) &&
            policy.accepted.id != policy.original.id && policy.accepted.visualVersion == policy.visualVersion) { "完整参考与扩图偏好不一致" }
        return policy.copy()
    }
    fun hash(policy: OcFramingPolicy): String {
        val p = check(policy)
        return LocalMemoryCodec.sha(LocalMemoryCodec.bytes(listOf("oc-framing-v1", p.original.id, p.original.sha256,
            p.original.visualVersion, p.profileReferenceId, p.framing.name, p.allowExpansion, p.revision, p.visualVersion,
            p.accepted?.id, p.accepted?.sha256, p.accepted?.visualVersion).joinToString("|") { it?.toString() ?: "null" }))
    }
    fun canExpand(policy: OcFramingPolicy): Boolean = check(policy).let {
        it.allowExpansion && it.accepted == null && it.framing in setOf(OcFraming.HEAD, OcFraming.BUST)
    }
    fun reference(policy: OcFramingPolicy): OcAssetRef = check(policy).let { it.accepted ?: it.original }
    fun permits(policy: OcFramingPolicy, fullBody: Boolean): Boolean = check(policy).let {
        !fullBody || it.framing == OcFraming.FULL || it.accepted != null
    }
}
