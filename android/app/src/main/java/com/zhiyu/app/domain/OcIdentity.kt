package com.zhiyu.app.domain

import java.security.MessageDigest
import java.util.UUID

/** Character identity is separate from provider/model/personality binding. Coordinates never mirror. */
data class OcIdentity(val ocId: String, val revision: Int, val route: String, val body: OcBody = OcBody(),
    val parts: List<OcPart> = emptyList(), val rig: OcRig = OcRigRules.defaultRig(), val reference: OcAssetRef? = null,
    val confirmed: Boolean = false, val schemaVersion: Int = 1, val photoSource: OcAssetRef? = null)
data class OcIdentitySnapshot(val identity: OcIdentity, val packageSha: String, val savedAt: Long)
data class OcPendingPublication(val identity: OcIdentity, val expectedRevision: Int, val expectedVisualVersion: Int,
    val pngSha: String, val createdAt: Long)
data class OcAssetRef(val id: String, val sha256: String, val visualVersion: Int)
data class OcBody(val headWidth: Float = 1f, val headHeight: Float = 1f, val eyeSize: Float = 1f,
    val eyeSpacing: Float = 1f, val noseSize: Float = 1f, val mouthWidth: Float = 1f,
    val bodyWidth: Float = 1f, val height: Float = 1f, val hairLength: Float = .35f,
    val skin: Int = 0xffffdccc.toInt(), val hair: Int = 0xff4e3f3a.toInt(),
    val eyes: Int = 0xff647c65.toInt(), val clothes: Int = 0xffb9bdb0.toInt(), val hairStyle: String = "soft")
data class OcPart(val id: String, val kind: String, val side: String = "center", val x: Float = .5f,
    val y: Float = .45f, val z: Float = -.1f, val width: Float = .28f, val height: Float = .36f,
    val angle: Float = 0f, val color: Int = 0xffddd9c5.toInt(), val layer: Int = -1, val label: String = "")
data class OcScene(val name: String = "portrait", val cameraYaw: Float = 0f, val cameraPitch: Float = 0f,
    val zoom: Float = 1f, val background: Int = 0xfffff8e9.toInt(), val motion: OcMotion = OcMotion())
data class OcMotion(val headTurn: Float = 0f, val breath: Float = 0f, val blink: Float = 0f,
    val mouthOpen: Float = 0f, val accessorySway: Float = 0f)

object OcIdentityRules {
    const val MAX_PARTS = 128 // resource guard, not a limit on pairs/anatomy
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,80}")
    private val sourceId = Regex("[a-f0-9]{32}")
    private val sha = Regex("[a-f0-9]{64}")
    val routes = setOf("unknown", "photo_subject", "parts_2d", "avatar_3d")
    val partKinds = setOf("wing", "weapon", "horn", "tail", "ear", "ornament", "custom")
    fun fresh(route: String, ocId: String = UUID.randomUUID().toString().replace("-", "")): OcIdentity =
        check(OcIdentity(ocId, 0, route))
    fun check(value: OcIdentity): OcIdentity {
        require(sourceId.matches(value.ocId) && value.revision >= 0 && value.schemaVersion == 1 && value.route in routes) { "角色档案无效" }
        checkBody(value.body)
        require(value.parts.size <= MAX_PARTS && value.parts.map { it.id }.distinct().size == value.parts.size) { "部件过多或编号重复" }
        value.parts.forEach(::checkPart)
        listOfNotNull(value.reference, value.photoSource).forEach { require(sourceId.matches(it.id) && sha.matches(it.sha256) && it.visualVersion >= 0) { "角色图片校验无效" } }
        val rig = OcRigRules.check(value.rig)
        return value.copy(parts = value.parts.map { it.copy() }, rig = rig)
    }
    fun next(before: OcIdentity, draft: OcIdentity): OcIdentity {
        require(before.ocId == draft.ocId && before.revision == draft.revision) { "角色已更新，请重新打开" }
        require(before.revision < Int.MAX_VALUE) { "角色修订数已满" }
        return check(draft.copy(revision = before.revision + 1, confirmed = true))
    }
    fun packageSha(value: OcIdentity): String {
        val v = check(value)
        val canonical = buildString {
            fun token(value: Any?) { val text = value?.toString() ?: "null"; append(text.length).append(':').append(text) }
            fun asset(value: OcAssetRef?) { token(value != null); value?.let { token(it.id); token(it.sha256); token(it.visualVersion) } }
            token("oc-v1"); token(v.ocId); token(v.revision); token(v.route); token(v.confirmed)
            val b = v.body
            listOf(b.headWidth, b.headHeight, b.eyeSize, b.eyeSpacing, b.noseSize, b.mouthWidth, b.bodyWidth, b.height, b.hairLength).forEach { token(it.toBits()) }
            listOf(b.skin, b.hair, b.eyes, b.clothes).forEach(::token); token(b.hairStyle)
            token(v.parts.size)
            v.parts.forEach { p ->
                token(p.id); token(p.kind); token(p.side); token(p.label); token(p.color); token(p.layer)
                listOf(p.x, p.y, p.z, p.width, p.height, p.angle).forEach { token(it.toBits()) }
            }
            token(v.rig.version); token(v.rig.regions.size)
            v.rig.regions.forEach { r ->
                token(r.id); token(r.kind); token(r.label)
                listOf(r.x, r.y, r.width, r.height, r.pivotX, r.pivotY, r.strength).forEach { token(it.toBits()) }
            }
            asset(v.reference); asset(v.photoSource)
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun scene(value: OcScene): OcScene {
        require(value.name.length in 1..80 && value.cameraYaw.isFinite() && value.cameraYaw in -180f..180f &&
            value.cameraPitch.isFinite() && value.cameraPitch in -60f..60f && value.zoom.isFinite() && value.zoom in .5f..2f) { "角色场景参数无效" }
        OcRigRules.motion(value.motion)
        return value
    }
    private fun checkBody(b: OcBody) {
        listOf(b.headWidth, b.headHeight, b.eyeSize, b.eyeSpacing, b.noseSize, b.mouthWidth, b.bodyWidth, b.height).forEach {
            require(it.isFinite() && it in .65f..1.35f) { "角色比例超出范围" }
        }
        require(b.hairLength.isFinite() && b.hairLength in 0f..1f && b.hairStyle in setOf("soft", "short", "side", "twin")) { "发型无效" }
        listOf(b.skin, b.hair, b.eyes, b.clothes).forEach { require(it ushr 24 == 255) { "角色颜色无效" } }
    }
    private fun checkPart(p: OcPart) {
        require(idPattern.matches(p.id) && p.kind in partKinds && p.side in setOf("left", "right", "center", "unspecified")) { "角色部件无效" }
        require(listOf(p.x, p.y, p.z, p.width, p.height, p.angle).all { it.isFinite() } && p.x in 0f..1f && p.y in 0f..1f &&
            p.z in -1f..1f && p.width in .02f.. .9f && p.height in .02f.. .9f && p.angle in -180f..180f && p.layer in -128..128) { "部件变换超出范围" }
        require(p.color ushr 24 == 255 && p.label.length <= 80 && p.label.none { it.code < 32 }) { "部件颜色或名称无效" }
    }
}
