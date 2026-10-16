package rig2d.domain

import java.security.MessageDigest

/*
 * ============================================================================
 * v1 契约冻结副本（FROZEN SNAPSHOT）
 * ============================================================================
 *
 * 本文件是 zhiyu 现有 v1 档案契约的逐行等价副本，用于满足实施计划 §2.4 的硬门禁：
 *
 *   「v1 逐字节回归是硬门禁：现有已发布角色的渲染输出不得有任何差异。」
 *
 * 冻结来源（只读参考，本仓库不得引用其编译产物）：
 *   android/app/src/main/java/com/zhiyu/app/domain/OcIdentity.kt   (OcIdentity / OcIdentityRules.packageSha)
 *   android/app/src/main/java/com/zhiyu/app/domain/OcRig.kt        (OcRig / OcRigRules.vertices)
 *   android/app/src/main/java/com/zhiyu/app/data/OcFlatRenderer.kt (Character.draw 的变换算术)
 *
 * 纪律：
 *   1. 本文件**一旦冻结不得再改**。任何 v2 改动都不得触碰这里的字段顺序、token 规则、常量。
 *   2. 文本前缀保持 "oc-v1"。v2 使用 "oc-v2"，两者序列化前缀不同，避免哈希碰撞（计划 §2.1）。
 *   3. 本副本的作用是让 v2 契约在一个**不依赖 Android** 的环境里被证明没有改动 v1 行为：
 *      移植时（docs/PORTING.md）把 v1 部分照抄替换真实源码，测试必须继续 GREEN。
 *
 * NOT_ANDROID：本副本不含任何 android.* 类型，因此可在纯 JVM 上运行。
 */

// ---------------------------------------------------------------- 数据结构 v1

/** 对应 OcIdentity。字段名、顺序、默认值必须与冻结来源一致。 */
data class OcIdentityV1(
    val ocId: String,
    val revision: Int,
    val route: String,
    val body: OcBodyV1 = OcBodyV1(),
    val parts: List<OcPartV1> = emptyList(),
    val rig: OcRigV1 = OcRigRulesV1.defaultRig(),
    val reference: OcAssetRefV1? = null,
    val confirmed: Boolean = false,
    val schemaVersion: Int = 1,
    val photoSource: OcAssetRefV1? = null,
)

data class OcAssetRefV1(val id: String, val sha256: String, val visualVersion: Int)

data class OcBodyV1(
    val headWidth: Float = 1f, val headHeight: Float = 1f, val eyeSize: Float = 1f,
    val eyeSpacing: Float = 1f, val noseSize: Float = 1f, val mouthWidth: Float = 1f,
    val bodyWidth: Float = 1f, val height: Float = 1f, val hairLength: Float = .35f,
    val skin: Int = 0xffffdccc.toInt(), val hair: Int = 0xff4e3f3a.toInt(),
    val eyes: Int = 0xff647c65.toInt(), val clothes: Int = 0xffb9bdb0.toInt(), val hairStyle: String = "soft",
)

data class OcPartV1(
    val id: String, val kind: String, val side: String = "center", val x: Float = .5f,
    val y: Float = .45f, val z: Float = -.1f, val width: Float = .28f, val height: Float = .36f,
    val angle: Float = 0f, val color: Int = 0xffddd9c5.toInt(), val layer: Int = -1, val label: String = "",
)

data class OcRigV1(val regions: List<OcRigRegionV1> = emptyList(), val version: Int = 1)

data class OcRigRegionV1(
    val id: String, val kind: String, val x: Float = .3f, val y: Float = .1f,
    val width: Float = .4f, val height: Float = .3f, val pivotX: Float = .5f, val pivotY: Float = .5f,
    val strength: Float = 1f, val label: String = "",
)

/** 对应 OcMotion。v1 与 v2 共用语义区间 -1..1；解释不同（见 MotionSemantics）。 */
data class OcMotionV1(
    val headTurn: Float = 0f, val breath: Float = 0f, val blink: Float = 0f,
    val mouthOpen: Float = 0f, val accessorySway: Float = 0f,
)

// ---------------------------------------------------------------- 校验 v1

/** 对应 OcIdentityRules 中 v1 相关部分。校验分支必须逐条等价。 */
object OcIdentityRulesV1 {
    const val MAX_PARTS = 128

    private val idPattern = Regex("[a-zA-Z0-9_-]{1,80}")
    private val sourceId = Regex("[a-f0-9]{32}")
    private val sha = Regex("[a-f0-9]{64}")

    /** 冻结集合。v2 新增路由**不得**在此出现（计划 §2.1：photo_rig_2d 是 v2 的事）。 */
    val routes = setOf("unknown", "photo_subject", "parts_2d", "avatar_3d")
    val partKinds = setOf("wing", "weapon", "horn", "tail", "ear", "ornament", "custom")

    fun check(value: OcIdentityV1): OcIdentityV1 {
        require(sourceId.matches(value.ocId) && value.revision >= 0 && value.schemaVersion == 1 && value.route in routes) { "角色档案无效" }
        checkBody(value.body)
        require(value.parts.size <= MAX_PARTS && value.parts.map { it.id }.distinct().size == value.parts.size) { "部件过多或编号重复" }
        value.parts.forEach(::checkPart)
        listOfNotNull(value.reference, value.photoSource).forEach {
            require(sourceId.matches(it.id) && sha.matches(it.sha256) && it.visualVersion >= 0) { "角色图片校验无效" }
        }
        val rig = OcRigRulesV1.check(value.rig)
        return value.copy(parts = value.parts.map { it.copy() }, rig = rig)
    }

    private fun checkBody(b: OcBodyV1) {
        listOf(b.headWidth, b.headHeight, b.eyeSize, b.eyeSpacing, b.noseSize, b.mouthWidth, b.bodyWidth, b.height).forEach {
            require(it.isFinite() && it in .65f..1.35f) { "角色比例超出范围" }
        }
        require(b.hairLength.isFinite() && b.hairLength in 0f..1f && b.hairStyle in setOf("soft", "short", "side", "twin")) { "发型无效" }
        listOf(b.skin, b.hair, b.eyes, b.clothes).forEach { require(it ushr 24 == 255) { "角色颜色无效" } }
    }

    private fun checkPart(p: OcPartV1) {
        require(idPattern.matches(p.id) && p.kind in partKinds && p.side in setOf("left", "right", "center", "unspecified")) { "角色部件无效" }
        require(listOf(p.x, p.y, p.z, p.width, p.height, p.angle).all { it.isFinite() } && p.x in 0f..1f && p.y in 0f..1f &&
            p.z in -1f..1f && p.width in .02f.. .9f && p.height in .02f.. .9f && p.angle in -180f..180f && p.layer in -128..128) { "部件变换超出范围" }
        require(p.color ushr 24 == 255 && p.label.length <= 80 && p.label.none { it.code < 32 }) { "部件颜色或名称无效" }
    }
}

/** 对应 OcRigRules。 */
object OcRigRulesV1 {
    fun defaultRig() = OcRigV1(
        listOf(
            OcRigRegionV1("head", "head"),
            OcRigRegionV1("body", "body", .25f, .4f, .5f, .55f),
            OcRigRegionV1("eyes", "eyes", .32f, .19f, .36f, .08f),
            OcRigRegionV1("mouth", "mouth", .42f, .29f, .16f, .07f),
        ),
    )

    fun check(value: OcRigV1): OcRigV1 {
        require(value.version == 1 && value.regions.size <= 128 && value.regions.map { it.id }.distinct().size == value.regions.size) { "动画区域过多或重复" }
        value.regions.forEach {
            require(Regex("[a-zA-Z0-9_-]{1,80}").matches(it.id) && it.kind in setOf("head", "body", "eyes", "mouth", "accessory")) { "动画区域无效" }
            require(listOf(it.x, it.y, it.width, it.height, it.pivotX, it.pivotY, it.strength).all { n -> n.isFinite() } &&
                it.x in 0f..1f && it.y in 0f..1f && it.width in .02f..1f && it.height in .02f..1f &&
                it.x + it.width <= 1.001f && it.y + it.height <= 1.001f && it.pivotX in 0f..1f && it.pivotY in 0f..1f &&
                it.strength in 0f..2f && it.label.length <= 80) { "动画区域超出图片" }
        }
        return value.copy(regions = value.regions.map { it.copy() })
    }

    fun motion(value: OcMotionV1): OcMotionV1 {
        require(listOf(value.headTurn, value.breath, value.blink, value.mouthOpen, value.accessorySway).all { it.isFinite() && it in -1f..1f }) { "动画参数无效" }
        require(value.blink >= 0f && value.mouthOpen >= 0f) { "表情参数无效" }
        return value
    }
}

// ---------------------------------------------------------------- packageSha v1

/**
 * 冻结的 canonical 长度为前缀 token 写入器。
 *
 * token 规则（必须逐字保持）：`append(text.length).append(':').append(text)`，null → "null"。
 * 这条规则是 v1 哈希可复现的全部原因；任何「更整洁」的重写都会改变哈希并触发硬门禁失败。
 */
class CanonicalBuilder {
    private val text = StringBuilder()

    fun token(value: Any?) {
        val t = value?.toString() ?: "null"
        text.append(t.length).append(':').append(t)
    }

    fun tokenBits(value: Float) = token(value.toBits())

    fun tokenInts(values: IntArray) = values.forEach { token(it) }

    fun build(): String = text.toString()
}

/** 对应 OcIdentityRules.packageSha。前缀为 "oc-v1"。 */
object V1PackageSha {
    const val PREFIX = "oc-v1"

    fun canonical(value: OcIdentityV1): String {
        val v = OcIdentityRulesV1.check(value)
        val c = CanonicalBuilder()
        fun asset(ref: OcAssetRefV1?) {
            c.token(ref != null)
            ref?.let { c.token(it.id); c.token(it.sha256); c.token(it.visualVersion) }
        }
        c.token(PREFIX); c.token(v.ocId); c.token(v.revision); c.token(v.route); c.token(v.confirmed)
        val b = v.body
        listOf(b.headWidth, b.headHeight, b.eyeSize, b.eyeSpacing, b.noseSize, b.mouthWidth, b.bodyWidth, b.height, b.hairLength)
            .forEach { c.tokenBits(it) }
        listOf(b.skin, b.hair, b.eyes, b.clothes).forEach { c.token(it) }
        c.token(b.hairStyle)
        c.token(v.parts.size)
        v.parts.forEach { p ->
            c.token(p.id); c.token(p.kind); c.token(p.side); c.token(p.label); c.token(p.color); c.token(p.layer)
            listOf(p.x, p.y, p.z, p.width, p.height, p.angle).forEach { c.tokenBits(it) }
        }
        c.token(v.rig.version); c.token(v.rig.regions.size)
        v.rig.regions.forEach { r ->
            c.token(r.id); c.token(r.kind); c.token(r.label)
            listOf(r.x, r.y, r.width, r.height, r.pivotX, r.pivotY, r.strength).forEach { c.tokenBits(it) }
        }
        asset(v.reference); asset(v.photoSource)
        return c.build()
    }

    fun sha(value: OcIdentityV1): String = sha256Hex(canonical(value).toByteArray(Charsets.UTF_8))
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** 对应 OcRigRules.motion 的 v1 语义校验（供 MotionSemantics 回归使用）。 */
object MotionRulesV1 {
    fun check(value: OcMotionV1): OcMotionV1 = OcRigRulesV1.motion(value)
}
