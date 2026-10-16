package rig2d.domain

/*
 * schema 分派与 packageSha 扩展（计划 §2.1）。
 *
 * 三件事：
 *   1. route 新增 `photo_rig_2d`。**不复用** `parts_2d` / `avatar_3d`——那是已取消的建模路线
 *      （CALL_CHARACTER_FIX_2026-10-11.md），复用其名字会让历史档案无法区分。
 *   2. v1 前缀仍为 "oc-v1"，v2 为 "oc-v2"，避免哈希碰撞。
 *   3. 分派函数 `schemaVersion == 1 → v1 路径`，其余交给 v2 校验；未知版本拒绝，不静默降级。
 */

object OcRoutes {
    /** v1 冻结集合（与 OcIdentityRulesV1.routes 一致）。 */
    val V1: Set<String> = OcIdentityRulesV1.routes

    const val PHOTO_RIG_2D = "photo_rig_2d"

    /** v2 集合 = v1 ∪ {photo_rig_2d}。v1 档案继续可读。 */
    val V2: Set<String> = V1 + PHOTO_RIG_2D
}

object OcSchemas {
    const val V1 = 1
    const val V2 = 2
    val SUPPORTED = setOf(V1, V2)

    /** 版本分派。未知版本一律拒绝（fail closed），不做「尽力而为」的解析。 */
    fun dispatch(schemaVersion: Int): Int = when {
        schemaVersion in SUPPORTED -> schemaVersion
        else -> throw IllegalArgumentException("不支持的角色档案版本：$schemaVersion")
    }
}

/**
 * v2 canonical 序列化。
 *
 * v1 的 canonical 由 [V1PackageSha] 冻结提供；v2 在其后追加 views/layers/proxy/margin，
 * 并换用 "oc-v2" 前缀。**同一个 v1 档案在两条路径下必须得到不同的哈希**（前缀不同），
 * 且 v2 内部「任一字段改动必变哈希」（计划 §2.3 的 OcPackageShaV2Test）。
 */
object OcPackageSha {
    const val V1_PREFIX = V1PackageSha.PREFIX
    const val V2_PREFIX = "oc-v2"

    /** v1 档案的哈希：必须与改动前逐字节相同。 */
    fun v1(identity: OcIdentityV1): String = V1PackageSha.sha(identity)

    fun v1Canonical(identity: OcIdentityV1): String = V1PackageSha.canonical(identity)

    /**
     * v2 资产哈希：`v2Prefix + v1 形状的骨架字段 + views/layers/proxy`。
     *
     * 顺序刻意固定为「视角按 yaw 降序、层按 z 升序」，这样同一资产的等价重排不会产生
     * 不同哈希；而任何语义字段（sha / z / depth / bounds / margin / 对称平面 / 网格尺寸）
     * 改动都必然改变哈希。
     */
    fun v2(asset: OcRigAsset): String = sha256Hex(v2Canonical(asset).toByteArray(Charsets.UTF_8))

    fun v2Canonical(asset: OcRigAsset): String {
        val checked = OcRigAssetRules.check(asset)
        val c = CanonicalBuilder()
        c.token(V2_PREFIX)
        c.token(OcRigAssetRules.SCHEMA_VERSION)
        c.token(checked.sourceSha)
        c.token(checked.approvedAt)
        val proxy = checked.proxy
        c.token(proxy != null)
        proxy?.let {
            c.token(it.version)
            assetRef(c, it.mesh)
            assetRef(c, it.depth)
            if (it.normal == null) c.token(false) else assetRef(c, it.normal)
            c.token(it.symmetryPlane.size)
            it.symmetryPlane.forEach { value -> c.tokenBits(value) }
        }
        c.token(checked.views.size)
        checked.views.sortedByDescending { it.yawCenter }.forEach { view ->
            c.token(view.viewId); c.tokenBits(view.yawCenter); c.tokenBits(view.yawHalfWidth)
            c.token(view.mesh.columns); c.token(view.mesh.rows); c.token(view.mesh.vertexCount)
            assetRef(c, view.mesh.asset)
            c.token(view.rig.version); c.token(view.rig.regions.size)
            view.rig.regions.forEach { region ->
                c.token(region.id); c.token(region.kind); c.token(region.label)
                listOf(region.x, region.y, region.width, region.height, region.pivotX, region.pivotY, region.strength)
                    .forEach { c.tokenBits(it) }
            }
            c.token(view.layers.size)
            view.layers.sortedBy { it.z }.forEach { layer ->
                c.token(layer.id); c.token(layer.kind); c.token(layer.z); c.tokenBits(layer.depth)
                assetRef(c, layer.asset)
                c.token(layer.bounds.size)
                layer.bounds.forEach { c.tokenBits(it) }
                c.tokenBits(layer.margin)
            }
        }
        return c.build()
    }

    private fun assetRef(c: CanonicalBuilder, ref: OcAssetRefV2) {
        c.token(true); c.token(ref.id); c.token(ref.sha256); c.token(ref.visualVersion)
    }
}
