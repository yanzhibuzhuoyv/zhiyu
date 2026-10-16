package rig2d.domain

/*
 * 测试夹具（fixtures）。
 *
 * 纪律：夹具里的 sha 全部是**合成值**，不是真实模型或真实图片的哈希。
 * 依据计划 §10.2：合成/测试数据必须显式标注。因此这些值统一用
 * `TEST_FIXTURE_*` 前缀的确定性内容生成，绝不冒充真实资产哈希。
 */

object Fixtures {
    /** 32 位十六进制，满足 v1/v2 的 sourceId 形态。 */
    const val OC_ID = "0123456789abcdef0123456789abcdef"

    /** 64 位十六进制合成哈希：内容确定，来源是测试夹具，不是真实文件。 */
    fun hex(seed: String): String {
        val raw = "TEST_FIXTURE_$seed".toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }

    fun ref(seed: String, visualVersion: Int = 1) = OcAssetRefV2("asset_$seed", hex(seed), visualVersion)

    /** 默认 24×32 网格 → 顶点数 (24+1)*(32+1) = 825。 */
    fun mesh(seed: String, columns: Int = 24, rows: Int = 32) =
        OcViewMesh(columns, rows, ref("mesh_$seed"), (columns + 1) * (rows + 1))

    fun layer(
        id: String,
        kind: String,
        z: Int,
        depth: Float,
        margin: Float = 0.12f,
        bounds: List<Float> = listOf(0.2f, 0.1f, 0.6f, 0.8f),
        seed: String = id,
    ) = OcLayerAsset(id, kind, z, depth, ref(seed), bounds, margin)

    /** 计划 §5.1 的 5 视角带。 */
    val CANONICAL_BANDS = listOf(
        ViewBand(OcViewConstants.FRONT, 0f, 30f),
        ViewBand(OcViewConstants.RIGHT45, 45f, 30f),
        ViewBand(OcViewConstants.RIGHT90, 90f, 30f),
        ViewBand(OcViewConstants.LEFT45, -45f, 30f),
        ViewBand(OcViewConstants.LEFT90, -90f, 30f),
    )

    /**
     * 一条视角的层栈。层深按视角侧别给：正面 0，±45° 给 ∓0.6 的平均深度，
     * ±90° 给 ±0.6——即「侧向视角的层整体更靠外」，这是真实资产会出现的形状。
     */
    fun view(viewId: String, yawCenter: Float, depthMean: Float, margin: Float = 0.12f): OcViewLayers {
        val layers = listOf(
            layer("${viewId}_back_hair", "back_hair", z = 0, depth = -depthMean, margin = margin),
            layer("${viewId}_face", "face", z = 10, depth = minOf(1f, depthMean * 0.6f), margin = margin),
            layer("${viewId}_front_hair", "front_hair", z = 30, depth = depthMean * 0.8f, margin = margin),
        )
        return OcViewLayers(
            viewId = viewId,
            yawCenter = yawCenter,
            yawHalfWidth = 30f,
            layers = layers,
            rig = OcRigRulesV1.defaultRig(),
            mesh = mesh(viewId),
        )
    }

    /** 计划锁定的 5 视角资产。5 视角齐全、带宽 30、深度均值 ±0.6。 */
    fun fiveViewAsset(
        sourceSha: String = hex("source"),
        margin: Float = 0.12f,
        approvedAt: Long = 1_760_000_000_000L,
        withProxy: Boolean = true,
    ): OcRigAsset {
        val views = listOf(
            view(OcViewConstants.FRONT, 0f, 0f, margin),
            view(OcViewConstants.RIGHT45, 45f, 0.6f, margin),
            view(OcViewConstants.RIGHT90, 90f, 0.6f, margin),
            view(OcViewConstants.LEFT45, -45f, -0.6f, margin),
            view(OcViewConstants.LEFT90, -90f, -0.6f, margin),
        )
        return OcRigAsset(
            views = views,
            sourceSha = sourceSha,
            approvedAt = approvedAt,
            proxy = if (withProxy) proxy() else null,
        )
    }

    fun proxy() = OcProxy(
        mesh = ref("proxy_mesh"),
        // 归一化法线 (0,0,1)（对称平面为 xy 平面），偏移 0。
        symmetryPlane = listOf(0f, 0f, 1f, 0f),
        depth = ref("proxy_depth"),
        normal = ref("proxy_normal"),
    )

    /** v1 档案夹具：一条真实形状的 parts_2d / photo_subject 档案（用于逐字节回归）。 */
    fun v1Identity(
        route: String = "photo_subject",
        confirmed: Boolean = true,
        revision: Int = 3,
        parts: List<OcPartV1>? = null,
    ): OcIdentityV1 = OcIdentityV1(
        ocId = OC_ID,
        revision = revision,
        route = route,
        body = OcBodyV1(headWidth = 1.02f, hairStyle = "soft", hairLength = 0.42f),
        parts = parts ?: listOf(
            OcPartV1("wing_a", "wing", side = "left", x = 0.31f, y = 0.52f, z = -0.2f, width = 0.24f, height = 0.3f, angle = 12f, layer = 2, label = "左翼"),
            OcPartV1("horn_b", "horn", side = "right", x = 0.62f, y = 0.28f, width = 0.18f, height = 0.22f, layer = 4),
        ),
        reference = OcAssetRefV1(OC_ID, hex("v1_reference"), 2),
        confirmed = confirmed,
    )

    fun v1Motion(headTurn: Float = 0.4f, breath: Float = 0.3f, blink: Float = 0.0f, mouthOpen: Float = 0.5f, accessorySway: Float = 0.2f) =
        OcMotionV1(headTurn = headTurn, breath = breath, blink = blink, mouthOpen = mouthOpen, accessorySway = accessorySway)
}
